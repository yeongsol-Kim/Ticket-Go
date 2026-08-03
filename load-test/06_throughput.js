/**
 * 시나리오 6: 2차 처리량 테스트 (스케줄러 config 스윕용)
 *
 * 목적:
 *   대기열 스케줄러의 처리율(admit-count / delay-ms)을 올려가며
 *   "오버부킹 0 + p95 < 1s"를 지키는 최대 처리량을 찾고,
 *   병목(낙관락 경합 / DB풀 / CPU)이 어디서 터지는지 규명한다.
 *
 * 1차와의 차이:
 *   - setup에서 유저를 만들지 않는다. seed_users.sh가 만든 tokens.json을
 *     SharedArray로 읽기만 한다 (10,000명도 setup 비용 0).
 *   - 오버부킹(발급 > 재고)을 임계값으로 강제 검증한다.
 *
 * 사전 준비 (매 회차):
 *   1) ./load-test/reset_ec2.sh                  # 클린 출발선 (재고/Redis/데이터)
 *   2) COUNT=<N> ./load-test/seed_users.sh       # tokens.json 생성 (최초 1회면 충분)
 *   3) EC2에서 config 변경 후 재시작:
 *      ADMIT_COUNT=50 DELAY_MS=1000 docker-compose up -d app
 *
 * 실행:
 *   k6 run -e BASE_URL=http://3.24.46.153:8080 -e STOCK=1000 load-test/06_throughput.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EVENT_ID = parseInt(__ENV.EVENT_ID || '3');
const STOCK = parseInt(__ENV.STOCK || '1000');            // 오버부킹 판정 기준
const MAX_WAIT_SECONDS = parseInt(__ENV.MAX_WAIT_SECONDS || '180');
const POLL_INTERVAL_SEC = parseFloat(__ENV.POLL_INTERVAL_SEC || '2');

// tokens.json을 VU 전체가 공유 (복사본이 VU마다 생기지 않음 = 메모리 절약)
const tokens = new SharedArray('tokens', function () {
  return JSON.parse(open('./tokens.json'));
});

const VUS = parseInt(__ENV.VUS || String(tokens.length));

// ── 메트릭 ──
const queueWaitDuration = new Trend('queue_wait_duration', true);  // 진입 → 세션 발급
const approvalDuration  = new Trend('approval_duration', true);    // 승인 응답
const e2eDuration       = new Trend('e2e_duration', true);         // 진입 → 승인 완료
const admitted          = new Counter('admitted');                 // 결제 세션 받은 인원
const confirmed         = new Counter('confirmed');                // 결제 승인 완료 = 실제 판매
const soldOut           = new Counter('sold_out');                 // 재고 소진으로 정상 탈락
const flowSuccess       = new Rate('flow_success');

// RAMP: >0이면 각 VU가 시작 시 (__VU/VUS)×RAMP초 지터 후 큐 진입 → t=0 동시 핸드셰이크 폭증
// (connection reset 유발)을 RAMP초에 걸쳐 분산. exactly-once(VU당 1회) 의미는 그대로 유지.
const RAMP = parseInt(__ENV.RAMP || '0');

export const options = {
  scenarios: {
    throughput: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: VUS,
      maxDuration: '15m',
    },
  },
  thresholds: {
    // ★ 핵심: 실제 판매량이 재고를 넘으면 오버부킹 = 즉시 실패
    'confirmed': [`count<=${STOCK}`],
    'approval_duration': ['p(95)<1000'],   // SLO: p95 < 1s
    'e2e_duration': ['p(95)<120000'],      // SLO: 2분 내 처리
  },
};

export function setup() {
  console.log(`[Setup] 토큰 ${tokens.length}개 로드, VU=${VUS}, 이벤트=${EVENT_ID}, 재고=${STOCK}`);
  if (tokens.length === 0) {
    throw new Error('tokens.json이 비어있습니다. seed_users.sh를 먼저 실행하세요.');
  }
  return { startedAt: Date.now() };
}

export default function () {
  const token = tokens[(__VU - 1) % tokens.length];
  const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` };

  // 램프업: VU 순서에 비례해 시작 시점을 RAMP초에 걸쳐 분산 (t=0 동시접속 폭증 방지)
  if (RAMP > 0) {
    sleep((__VU / VUS) * RAMP);
  }

  const t0 = Date.now();

  // 1단계: 대기열 진입
  const enterRes = http.post(
    `${BASE_URL}/api/queue/enter`,
    JSON.stringify({ eventId: EVENT_ID, ticketCount: 1 }),
    { headers }
  );

  if (!check(enterRes, { '대기열 진입 200': (r) => r.status === 200 })) {
    flowSuccess.add(false);
    return;
  }

  // 2단계: 결제 세션 발급 대기 (스케줄러가 처리해줄 때까지 폴링)
  let bookingId = null;
  const deadline = Date.now() + MAX_WAIT_SECONDS * 1000;

  while (Date.now() < deadline) {
    sleep(POLL_INTERVAL_SEC);

    const statusRes = http.get(`${BASE_URL}/api/queue/status/${EVENT_ID}`, { headers });

    if (statusRes.status !== 200) {
      // 400/404 = 큐에서 빠짐(재고 소진 등) → 더 기다릴 이유 없음
      if (statusRes.status === 400 || statusRes.status === 404) break;
      continue;
    }

    const status = JSON.parse(statusRes.body);
    if (status.isActive) {
      bookingId = status.bookingId;
      break;
    }
  }

  if (!bookingId) {
    // 재고가 이미 소진됐다면 세션 미발급은 "정상 탈락"이지 실패가 아니다.
    // (오버부킹 방지가 제대로 동작한 결과이므로 구분해서 집계)
    soldOut.add(1);
    flowSuccess.add(false);
    return;
  }

  queueWaitDuration.add(Date.now() - t0);
  admitted.add(1);

  // 3단계: 결제 요청
  const payReqRes = http.post(
    `${BASE_URL}/api/payments`,
    JSON.stringify({ bookingId, method: 'CARD' }),
    { headers }
  );

  if (!check(payReqRes, { '결제 요청 201': (r) => r.status === 201 })) {
    flowSuccess.add(false);
    return;
  }

  const payment = JSON.parse(payReqRes.body);

  // 4단계: 결제 승인 (여기서 재고 확정 = 오버부킹이 드러나는 지점)
  const approveStart = Date.now();
  const approveRes = http.post(
    `${BASE_URL}/api/payments/${payment.id}/approve`,
    JSON.stringify({ paymentKey: `k6-vu${__VU}-${Date.now()}` }),
    { headers }
  );
  approvalDuration.add(Date.now() - approveStart);

  const ok = check(approveRes, { '결제 승인 200': (r) => r.status === 200 });
  flowSuccess.add(ok);

  if (ok) {
    confirmed.add(1);
    e2eDuration.add(Date.now() - t0);
  }
}

export function teardown() {
  console.log('');
  console.log('=== 확인할 것 ===');
  console.log(`  1) confirmed <= ${STOCK} 인가?  (초과 시 오버부킹 = 치명적 버그)`);
  console.log('  2) approval_duration p95 < 1000ms 인가?');
  console.log('  3) Grafana: queue_admission_lock_conflict 가 증가했는가? (@Version 발동 지점)');
  console.log('  4) Grafana: hikaricp_connections_pending 이 쌓였는가? (DB풀 병목)');
  console.log('  5) EC2 DB 검증: SELECT available_tickets FROM events WHERE id=' + EVENT_ID + ';  (음수면 오버부킹)');
}

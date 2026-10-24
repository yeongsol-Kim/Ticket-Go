/**
 * 시나리오 7: 폴링 + SSE 하이브리드 처리량 테스트 (4차, 06_throughput.js의 하이브리드 버전)
 *
 * 06과 흐름·지표·RAW 로그 형식이 같고, "결제 세션 발급 대기" 단계만 다르다.
 *   06: GET /api/queue/status 폴링만 (서버가 준 nextPollSeconds만큼 쉰다)
 *   07: 차례가 멀면 06과 같이 폴링, 서버가 streamRecommended=true를 주면 (예상 대기 ≤ 전환 기준)
 *       GET /api/queue/stream SSE로 갈아타 admitted를 기다린다.
 *       SSE가 거절(use-polling)되거나 끊기면 다시 폴링.
 *   (전원을 SSE로 붙이면 Tomcat 연결당 ~100KB라 1만 명 ~1GB → 2GB 서버에서 OOM. 4차 실측)
 *
 * 일반 k6에는 SSE가 없어 xk6-sse를 넣어 빌드한 k6로 실행한다:
 *   xk6 build v2.0.0 --with github.com/phymbert/xk6-sse --output k6-sse
 *   (같은 날 06과 비교할 때도 이 바이너리 하나로 둘 다 돌려 도구 차이를 없앤다)
 *
 * 주의: SSE 중인 VU는 일반 HTTP 연결 + SSE 연결 2개를 연다. Tomcat max-connections를 넉넉히 둘 것.
 *
 * 실행:
 *   ./k6-sse run -e BASE_URL=http://172.31.13.62:8080 -e STOCK=10000 -e RAW_LOG=1 load-test/07_sse.js
 */

import http from 'k6/http';
import sse from 'k6/x/sse';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EVENT_ID = parseInt(__ENV.EVENT_ID || '3');
const STOCK = parseInt(__ENV.STOCK || '1000');            // 오버부킹 판정 기준
const MAX_WAIT_SECONDS = parseInt(__ENV.MAX_WAIT_SECONDS || '180');
const POLL_INTERVAL_SEC = parseFloat(__ENV.POLL_INTERVAL_SEC || '2');  // SSE 실패 시 폴링 대체용
const RAW_LOG = __ENV.RAW_LOG === '1';

const tokens = new SharedArray('tokens', function () {
  return JSON.parse(open('./tokens.json'));
});

const VUS = parseInt(__ENV.VUS || String(tokens.length));

// ── 메트릭 (06과 같은 이름 + SSE 전용) ──
const queueWaitDuration = new Trend('queue_wait_duration', true);  // 진입 → 세션 발급 인지
const approvalDuration  = new Trend('approval_duration', true);    // 승인 응답
const e2eDuration       = new Trend('e2e_duration', true);         // 진입 → 승인 완료
const notifyLag         = new Trend('sse_notify_lag', true);       // 서버 전송(sentAt) → 클라이언트 수신
const sseOpened         = new Counter('sse_opened');               // SSE로 갈아탄 횟수
const sseRejected       = new Counter('sse_rejected');             // 연결 상한으로 거절(use-polling)
const admitted          = new Counter('admitted');
const confirmed         = new Counter('confirmed');
const soldOut           = new Counter('sold_out');
const sseFallback       = new Counter('sse_fallback');             // SSE가 admitted 없이 끝나 폴링으로 돌아간 횟수
const flowSuccess       = new Rate('flow_success');

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
    'confirmed': [`count<=${STOCK}`],
    'approval_duration': ['p(95)<1000'],
    'e2e_duration': ['p(95)<120000'],
  },
};

export function setup() {
  console.log(`[Setup] 하이브리드(폴링+SSE) 모드, 토큰 ${tokens.length}개 로드, VU=${VUS}, 이벤트=${EVENT_ID}, 재고=${STOCK}`);
  if (tokens.length === 0) {
    throw new Error('tokens.json이 비어있습니다. seed_users.sh를 먼저 실행하세요.');
  }
}

export default function () {
  const token = tokens[(__VU - 1) % tokens.length];
  const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` };
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

  // 2단계: 결제 세션 발급 대기 - 멀면 폴링, 서버가 권하면 SSE
  const bookingId = waitForAdmission(enterRes, headers, token);

  if (!bookingId) {
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

  // 4단계: 결제 승인
  const approveStart = Date.now();
  const approveRes = http.post(
    `${BASE_URL}/api/payments/${payment.id}/approve`,
    JSON.stringify({ paymentKey: `k6-vu${__VU}-${Date.now()}` }),
    { headers }
  );
  const approvalMs = Date.now() - approveStart;
  approvalDuration.add(approvalMs);
  if (RAW_LOG) console.log(`RAW approval_ms=${approvalMs} enter_ms=${t0} end_ms=${approveStart + approvalMs} status=${approveRes.status}`);

  const ok = check(approveRes, { '결제 승인 200': (r) => r.status === 200 });
  flowSuccess.add(ok);
  if (ok) {
    confirmed.add(1);
    e2eDuration.add(Date.now() - t0);
  }
}

// 진입 응답부터 시작해 폴링하다가, streamRecommended면 SSE로 갈아탄다
function waitForAdmission(firstRes, headers, token) {
  const deadline = Date.now() + MAX_WAIT_SECONDS * 1000;
  let res = firstRes;
  while (Date.now() < deadline) {
    if (res.status === 400 || res.status === 404) return null;   // 큐에서 빠짐(매진 등)
    let body = null;
    if (res.status === 200) {
      body = JSON.parse(res.body);
      if (body.isActive) return body.bookingId;
      if (body.streamRecommended) {
        const r = waitBySse(token);
        if (r.bookingId) return r.bookingId;
        if (r.notInQueue) return null;
        // 거절·끊김 → 폴링 간격만큼 쉬고 다시 묻는다 (상한 근처에서 SSE 재시도가 폭주하지 않게)
      }
    }
    const wait = body && body.nextPollSeconds > 0 ? body.nextPollSeconds : POLL_INTERVAL_SEC;
    sleep(wait);
    res = http.get(`${BASE_URL}/api/queue/status/${EVENT_ID}`, { headers });
  }
  return null;
}

function waitBySse(token) {
  const result = { bookingId: null, notInQueue: false };
  sseOpened.add(1);
  sse.open(`${BASE_URL}/api/queue/stream/${EVENT_ID}`,
    { method: 'GET', headers: { 'Authorization': `Bearer ${token}` }, tags: { name: 'queue_stream' } },
    function (client) {
      client.on('event', function (event) {
        if (event.name === 'admitted') {
          const d = JSON.parse(event.data);
          result.bookingId = d.bookingId;
          notifyLag.add(Date.now() - d.sentAt);
          client.close();
        } else if (event.name === 'not-in-queue') {
          result.notInQueue = true;
          client.close();
        } else if (event.name === 'use-polling') {
          sseRejected.add(1);
          client.close();
        }
      });
      client.on('error', function () {
        // 연결 실패·끊김 → 폴링으로 돌아감
      });
    });
  if (!result.bookingId && !result.notInQueue) sseFallback.add(1);
  return result;
}

export function teardown() {
  console.log('');
  console.log('=== 확인할 것 ===');
  console.log(`  1) confirmed <= ${STOCK} 인가?  (초과 시 오버부킹)`);
  console.log('  2) approval_duration p95 < 1000ms 인가? (분산 실행이면 RAW 합산으로 판정)');
  console.log('  3) sse_opened / sse_rejected / sse_fallback: SSE로 갈아탄 횟수, 상한 거절, admitted 없이 끝남');
  console.log('  4) sse_notify_lag: 서버 전송 → 수신 지연');
}

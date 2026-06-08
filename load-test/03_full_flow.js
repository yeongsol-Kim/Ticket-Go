/**
 * 시나리오 3: E2E 전체 플로우 성능 테스트
 *
 * 목적: 실제 구매 플로우 전체(대기열 → 예약 → 결제)의 처리량과 응답시간 측정
 *
 * 플로우:
 *   1. 대기열 진입   POST /api/queue/enter
 *   2. 상태 폴링     GET  /api/queue/status/{eventId}  (isActive=true 될 때까지)
 *   3. 결제 요청     POST /api/payments
 *   4. 결제 승인     POST /api/payments/{id}/approve   (PG 콜백 시뮬레이션)
 *   5. 결과 검증     GET  /api/bookings/me  → CONFIRMED 상태 확인
 *
 * 기대 결과:
 *   - 전체 플로우 성공률 95% 이상
 *   - 결제 승인 응답 2초 이내 (p95)
 *
 * 주의: 이벤트 1 (BTS 콘서트, 1000장)을 사용합니다.
 *       시나리오 2 실행 후 Event ID=1은 소진되지 않으므로 반복 실행 가능합니다.
 *
 * 실행: k6 run load-test/03_full_flow.js
 * 옵션: k6 run -e EVENT_ID=1 -e NUM_USERS=20 load-test/03_full_flow.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { setupUser, authHeaders, JSON_HEADERS, BASE_URL } from './helpers.js';

// 커스텀 메트릭
const flowDuration       = new Trend('full_flow_duration', true); // 전체 플로우 시간
const queueWaitDuration  = new Trend('queue_wait_duration', true); // 대기열 대기 시간
const paymentDuration    = new Trend('payment_duration', true);    // 결제 처리 시간
const flowSuccessRate    = new Rate('flow_success');
const flowFailCount      = new Counter('flow_fail');

const NUM_USERS         = parseInt(__ENV.NUM_USERS  || '50');
const EVENT_ID          = parseInt(__ENV.EVENT_ID   || '1');  // BTS 콘서트 (1000장)
const MAX_WAIT_SECONDS  = 120;  // 50명 → 5사이클(25초) + 여유
const POLL_INTERVAL_SEC = 2;

export const options = {
  vus: NUM_USERS,
  iterations: NUM_USERS,
  setupTimeout: '120s',

  thresholds: {
    http_req_failed:    ['rate<0.05'],         // 전체 HTTP 에러 5% 미만
    flow_success:       ['rate>0.95'],         // 플로우 성공률 95% 이상
    payment_duration:   ['p(95)<2000'],        // 결제 처리 p95 < 2초
    full_flow_duration: ['p(95)<120000'],      // 전체 플로우 p95 < 2분
  },
};

export function setup() {
  console.log(`[Setup] 이벤트 ${EVENT_ID} - ${NUM_USERS}명 E2E 플로우 준비 중...`);
  const users = [];
  // 시나리오 1,2와 유저 겹침 방지 → lt201~ 사용
  for (let i = 201; i <= 200 + NUM_USERS; i++) {
    const user = setupUser(i);
    if (user.token) users.push(user);
  }
  console.log(`[Setup] 완료: ${users.length}명`);
  return users;
}

export default function (users) {
  const user = users[__VU - 1];
  if (!user || !user.token) return;

  const flowStart = Date.now();
  let success = false;

  try {
    // ── 1단계: 대기열 진입 ──────────────────────────────────────
    const enterRes = http.post(
      `${BASE_URL}/api/queue/enter`,
      JSON.stringify({ eventId: EVENT_ID, ticketCount: 1 }),
      { headers: authHeaders(user.token) }
    );

    if (!check(enterRes, { '[1] 대기열 진입 200': (r) => r.status === 200 })) {
      console.error(`[VU ${__VU}] 1단계 실패: ${enterRes.status}`);
      flowFailCount.add(1);
      return;
    }
    console.log(`[VU ${__VU}] [1/5] 대기열 진입 완료`);

    // ── 2단계: 결제 세션 대기 폴링 ──────────────────────────────
    const queueStart = Date.now();
    let bookingId    = null;
    const deadline   = Date.now() + MAX_WAIT_SECONDS * 1000;

    while (Date.now() < deadline) {
      sleep(POLL_INTERVAL_SEC);

      const statusRes = http.get(
        `${BASE_URL}/api/queue/status/${EVENT_ID}`,
        { headers: authHeaders(user.token) }
      );

      if (statusRes.status !== 200) {
        if (statusRes.status === 400 || statusRes.status === 404) break;
        continue;
      }

      const status = JSON.parse(statusRes.body);
      if (status.isActive) {
        bookingId = status.bookingId;
        break;
      }
    }

    queueWaitDuration.add(Date.now() - queueStart);

    if (!bookingId) {
      console.error(`[VU ${__VU}] 2단계 실패: 결제 세션 미발급 (타임아웃 또는 재고 소진)`);
      flowFailCount.add(1);
      return;
    }
    console.log(`[VU ${__VU}] [2/5] 결제 세션 발급: bookingId=${bookingId}`);

    // ── 3단계: 결제 요청 ────────────────────────────────────────
    const payStart  = Date.now();
    const payReqRes = http.post(
      `${BASE_URL}/api/payments`,
      JSON.stringify({ bookingId: bookingId, method: 'CARD' }),
      { headers: authHeaders(user.token) }
    );

    if (!check(payReqRes, { '[3] 결제 요청 201': (r) => r.status === 201 })) {
      console.error(`[VU ${__VU}] 3단계 실패: ${payReqRes.status} ${payReqRes.body}`);
      flowFailCount.add(1);
      return;
    }

    const payment = JSON.parse(payReqRes.body);
    console.log(`[VU ${__VU}] [3/5] 결제 요청 완료: paymentId=${payment.id}`);

    // ── 4단계: 결제 승인 (PG 콜백 시뮬레이션) ─────────────────
    const approveRes = http.post(
      `${BASE_URL}/api/payments/${payment.id}/approve`,
      JSON.stringify({ paymentKey: `test-key-vu${__VU}-${Date.now()}` }),
      { headers: authHeaders(user.token) }
    );

    paymentDuration.add(Date.now() - payStart);

    if (!check(approveRes, { '[4] 결제 승인 200': (r) => r.status === 200 })) {
      console.error(`[VU ${__VU}] 4단계 실패: ${approveRes.status}`);
      flowFailCount.add(1);
      return;
    }
    console.log(`[VU ${__VU}] [4/5] 결제 승인 완료`);

    // ── 5단계: 예약 확정 검증 ───────────────────────────────────
    const bookingsRes = http.get(
      `${BASE_URL}/api/bookings/me`,
      { headers: authHeaders(user.token) }
    );

    const verified = check(bookingsRes, {
      '[5] 예약 조회 200':       (r) => r.status === 200,
      '[5] CONFIRMED 예약 존재': (r) => {
        try {
          const bookings = JSON.parse(r.body);
          return bookings.some(
            (b) => b.id === bookingId && b.status === 'CONFIRMED'
          );
        } catch { return false; }
      },
    });

    if (!verified) {
      console.error(`[VU ${__VU}] 5단계 실패: 예약 CONFIRMED 아님`);
      flowFailCount.add(1);
      return;
    }

    console.log(`[VU ${__VU}] [5/5] 예약 확정 검증 완료 ✓`);
    success = true;

  } finally {
    flowDuration.add(Date.now() - flowStart);
    flowSuccessRate.add(success);

    if (!success) {
      console.error(`[VU ${__VU}] 전체 플로우 실패`);
    }
  }
}

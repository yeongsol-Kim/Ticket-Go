/**
 * 시나리오 5a: INSERT 발급 방식 단독 측정
 *
 * 목적: 05 통합 실험의 통제 실패(실행 순서) 검증용.
 *   INSERT만 단독 실행해, UPDATE와 "같은 출발선(각자 setup 직후)"에서 측정한다.
 *   05b(UPDATE 단독)와 성공률이 비슷하게 나오면 → 기존 50% vs 89% 차이는
 *   발급 방식이 아니라 실행 순서(서버 회복 상태) 탓이었음이 증명된다.
 *
 * 실행: k6 run -e BASE_URL=http://<EC2_IP>:8080 load-test/05a_insert_only.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { setupUser, authHeaders, BASE_URL } from './helpers.js';

const approvalDuration = new Trend('insert_approval_duration', true);
const successRate      = new Rate('insert_success');

const NUM_USERS         = parseInt(__ENV.NUM_USERS || '200');
const EVENT_ID          = parseInt(__ENV.EVENT_ID || '3');
const MAX_WAIT_SECONDS  = parseInt(__ENV.MAX_WAIT_SECONDS || '180');
const POLL_INTERVAL_SEC = 2;

export const options = {
  scenarios: {
    insert_method: {
      executor: 'shared-iterations',
      vus: NUM_USERS,
      iterations: NUM_USERS,
      maxDuration: '10m',
    },
  },
  setupTimeout: '600s',
  thresholds: {
    'insert_approval_duration': ['p(95)<3000'],
    'insert_success': ['rate>0.95'],
  },
};

export function setup() {
  console.log(`[Setup] INSERT용 ${NUM_USERS}명 준비 중...`);
  const users = [];
  for (let i = 301; i <= 300 + NUM_USERS; i++) {
    const user = setupUser(i);
    if (user.token) users.push(user);
  }
  console.log(`[Setup] 완료 - ${users.length}명`);
  return { users };
}

export default function ({ users }) {
  const user = users[__VU - 1];
  if (!user || !user.token) return;

  // 1단계: 대기열 진입
  const enterRes = http.post(
    `${BASE_URL}/api/queue/enter`,
    JSON.stringify({ eventId: EVENT_ID, ticketCount: 1 }),
    { headers: authHeaders(user.token) }
  );

  if (!check(enterRes, { '대기열 진입 200': (r) => r.status === 200 })) {
    console.error(`[VU ${__VU}][insert] 대기열 진입 실패: ${enterRes.status}`);
    successRate.add(false);
    return;
  }

  // 2단계: 결제 세션 대기
  let bookingId = null;
  const deadline = Date.now() + MAX_WAIT_SECONDS * 1000;

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

  if (!bookingId) {
    console.error(`[VU ${__VU}][insert] 결제 세션 미발급`);
    successRate.add(false);
    return;
  }

  // 3단계: 결제 요청
  const payReqRes = http.post(
    `${BASE_URL}/api/payments`,
    JSON.stringify({ bookingId, method: 'CARD' }),
    { headers: authHeaders(user.token) }
  );

  if (!check(payReqRes, { '결제 요청 201': (r) => r.status === 201 })) {
    console.error(`[VU ${__VU}][insert] 결제 요청 실패: ${payReqRes.status}`);
    successRate.add(false);
    return;
  }

  const payment = JSON.parse(payReqRes.body);

  // 4단계: 결제 승인 (INSERT)
  const approveStart = Date.now();
  const approveRes = http.post(
    `${BASE_URL}/api/payments/${payment.id}/approve`,
    JSON.stringify({ paymentKey: `test-key-vu${__VU}-${Date.now()}` }),
    { headers: authHeaders(user.token) }
  );
  const approveDuration = Date.now() - approveStart;

  const success = check(approveRes, { '결제 승인 200': (r) => r.status === 200 });
  approvalDuration.add(approveDuration);
  successRate.add(success);

  if (!success) {
    console.error(`[VU ${__VU}][insert] 결제 승인 실패: ${approveRes.status} ${approveRes.body}`);
  } else {
    console.log(`[VU ${__VU}][insert] 완료 - 승인 응답시간: ${approveDuration}ms`);
  }
}

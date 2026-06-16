/**
 * 시나리오 5: INSERT vs UPDATE 티켓 발급 방식 성능 비교
 *
 * 목적: 결제 승인 시 티켓 발급 방식에 따른 응답시간 비교
 *   - INSERT 방식: 결제 승인 시 티켓을 새로 생성 (현재 방식)
 *   - UPDATE 방식: 사전 발급된 티켓에 bookingId 할당 (Pre-issued)
 *
 * 실행: k6 run load-test/05_insert_vs_update.js
 * 옵션: k6 run -e BASE_URL=http://... -e EVENT_ID=1 load-test/05_insert_vs_update.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { setupUser, loginAdmin, preIssueTickets, authHeaders, JSON_HEADERS, BASE_URL } from './helpers.js';

// 커스텀 메트릭 - 두 방식 응답시간 분리 측정
const insertApprovalDuration = new Trend('insert_approval_duration', true);
const updateApprovalDuration = new Trend('update_approval_duration', true);
const insertSuccessRate      = new Rate('insert_success');
const updateSuccessRate      = new Rate('update_success');

const NUM_USERS         = 50;
const EVENT_ID          = parseInt(__ENV.EVENT_ID || '1');
const MAX_WAIT_SECONDS  = 120;
const POLL_INTERVAL_SEC = 2;

export const options = {
  scenarios: {
    insert_method: {
      executor: 'shared-iterations',
      vus: NUM_USERS,
      iterations: NUM_USERS,
      maxDuration: '5m',
      env: { METHOD: 'insert' },
      tags: { method: 'insert' },
    },
    update_method: {
      executor: 'shared-iterations',
      vus: NUM_USERS,
      iterations: NUM_USERS,
      maxDuration: '5m',
      startTime: '6m',  // insert 방식 완료 후 시작
      env: { METHOD: 'update' },
      tags: { method: 'update' },
    },
  },
  setupTimeout: '300s',
  thresholds: {
    'insert_approval_duration': ['p(95)<3000'],
    'update_approval_duration': ['p(95)<3000'],
    'insert_success': ['rate>0.95'],
    'update_success': ['rate>0.95'],
  },
};

export function setup() {
  console.log(`[Setup] ${NUM_USERS * 2}명 유저 준비 중...`);

  // INSERT 방식용 유저: lt301~350
  const insertUsers = [];
  for (let i = 301; i <= 300 + NUM_USERS; i++) {
    const user = setupUser(i);
    if (user.token) insertUsers.push(user);
  }

  // UPDATE 방식용 유저: lt351~400
  const updateUsers = [];
  for (let i = 351; i <= 350 + NUM_USERS; i++) {
    const user = setupUser(i);
    if (user.token) updateUsers.push(user);
  }

  // Pre-issued 티켓 사전 발급 (UPDATE 방식용)
  const adminToken = loginAdmin();
  if (!adminToken) {
    console.error('[Setup] 어드민 로그인 실패');
  } else {
    preIssueTickets(adminToken, EVENT_ID, NUM_USERS);
  }

  console.log(`[Setup] 완료 - INSERT용: ${insertUsers.length}명, UPDATE용: ${updateUsers.length}명`);
  return { insertUsers, updateUsers };
}

export default function ({ insertUsers, updateUsers }) {
  const method = __ENV.METHOD;
  const users  = method === 'insert' ? insertUsers : updateUsers;
  const user   = users[__VU - 1];

  if (!user || !user.token) return;

  // 1단계: 대기열 진입
  const enterRes = http.post(
    `${BASE_URL}/api/queue/enter`,
    JSON.stringify({ eventId: EVENT_ID, ticketCount: 1 }),
    { headers: authHeaders(user.token) }
  );

  if (!check(enterRes, { '대기열 진입 200': (r) => r.status === 200 })) {
    console.error(`[VU ${__VU}][${method}] 대기열 진입 실패: ${enterRes.status}`);
    method === 'insert' ? insertSuccessRate.add(false) : updateSuccessRate.add(false);
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
    console.error(`[VU ${__VU}][${method}] 결제 세션 미발급`);
    method === 'insert' ? insertSuccessRate.add(false) : updateSuccessRate.add(false);
    return;
  }

  // 3단계: 결제 요청
  const payReqRes = http.post(
    `${BASE_URL}/api/payments`,
    JSON.stringify({ bookingId, method: 'CARD' }),
    { headers: authHeaders(user.token) }
  );

  if (!check(payReqRes, { '결제 요청 201': (r) => r.status === 201 })) {
    console.error(`[VU ${__VU}][${method}] 결제 요청 실패: ${payReqRes.status}`);
    method === 'insert' ? insertSuccessRate.add(false) : updateSuccessRate.add(false);
    return;
  }

  const payment = JSON.parse(payReqRes.body);

  // 4단계: 결제 승인 - 방식에 따라 다른 엔드포인트
  const approveUrl = method === 'insert'
    ? `${BASE_URL}/api/payments/${payment.id}/approve`
    : `${BASE_URL}/api/payments/${payment.id}/approve-v2`;

  const approveStart = Date.now();
  const approveRes = http.post(
    approveUrl,
    JSON.stringify({ paymentKey: `test-key-vu${__VU}-${Date.now()}` }),
    { headers: authHeaders(user.token) }
  );
  const approveDuration = Date.now() - approveStart;

  const success = check(approveRes, { '결제 승인 200': (r) => r.status === 200 });

  if (method === 'insert') {
    insertApprovalDuration.add(approveDuration);
    insertSuccessRate.add(success);
  } else {
    updateApprovalDuration.add(approveDuration);
    updateSuccessRate.add(success);
  }

  if (!success) {
    console.error(`[VU ${__VU}][${method}] 결제 승인 실패: ${approveRes.status} ${approveRes.body}`);
  } else {
    console.log(`[VU ${__VU}][${method}] 완료 - 승인 응답시간: ${approveDuration}ms`);
  }
}

export function teardown() {
  console.log('');
  console.log('========================================');
  console.log('  [INSERT vs UPDATE 비교 결과]');
  console.log('  insert_approval_duration: INSERT 방식 결제 승인 응답시간');
  console.log('  update_approval_duration: UPDATE 방식 결제 승인 응답시간');
  console.log('========================================');
}

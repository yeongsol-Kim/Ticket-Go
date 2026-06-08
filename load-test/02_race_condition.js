/**
 * 시나리오 2: 재고 Race Condition
 *
 * 목적: 티켓 50장짜리 이벤트에 100명이 동시 진입 → 정확히 50건만 예약되는지 검증
 *       낙관적 락(@Version) + 스케줄러 기반 순차 처리가 제대로 동작하는지 확인
 *
 * 기대 결과:
 *   - got_booking <= 50  (티켓 초과 발급 금지 - 핵심 검증)
 *   - no_booking  >= 50  (나머지는 못 받음 = 정상)
 *   - 에러율 < 5%
 *
 * 주의: DataInitializer가 생성한 Event ID=4 (한정판 팬미팅, 50장)를 사용합니다.
 *       DB가 초기화된 상태(tickets 소진 전)에서 실행하세요.
 *
 * 실행: k6 run load-test/02_race_condition.js
 * 옵션: k6 run -e EVENT_ID=4 load-test/02_race_condition.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { setupUser, authHeaders, BASE_URL } from './helpers.js';

// 커스텀 메트릭
const gotBooking  = new Counter('got_booking');   // 결제 세션 발급 받은 인원
const noBooking   = new Counter('no_booking');    // 못 받은 인원 (티켓 소진)
const enterErrors = new Rate('queue_enter_error');

const NUM_USERS          = 100;
const EVENT_ID           = parseInt(__ENV.EVENT_ID || '4'); // 50장 한정
const MAX_WAIT_SECONDS   = 90;   // 최대 대기 (스케줄러 10명/5초 → 50명까지 ~25초)
const POLL_INTERVAL_SEC  = 3;

export const options = {
  vus: NUM_USERS,
  iterations: NUM_USERS,
  setupTimeout: '300s',

  thresholds: {
    http_req_failed: ['rate<0.05'],
    // 핵심: 예약 성공 건수가 이벤트 티켓 수(50)를 절대 초과하면 안 됨
    'got_booking': ['count<=50'],
  },
};

export function setup() {
  console.log(`[Setup] 이벤트 ${EVENT_ID} (50장 한정) - ${NUM_USERS}명 준비 중...`);
  const users = [];
  // 시나리오 1(lt1~100)과 유저 겹침 방지 → lt101~ 사용
  for (let i = 101; i <= 100 + NUM_USERS; i++) {
    const user = setupUser(i);
    if (user.token) users.push(user);
  }
  console.log(`[Setup] 완료: ${users.length}명`);
  return users;
}

export default function (users) {
  const user = users[__VU - 1];
  if (!user || !user.token) return;

  // ── 1단계: 대기열 진입 ────────────────────────────────────────
  const enterRes = http.post(
    `${BASE_URL}/api/queue/enter`,
    JSON.stringify({ eventId: EVENT_ID, ticketCount: 1 }),
    { headers: authHeaders(user.token) }
  );

  enterErrors.add(enterRes.status !== 200);

  if (!check(enterRes, { '대기열 진입 성공': (r) => r.status === 200 })) {
    console.error(`[VU ${__VU}] 진입 실패: ${enterRes.status} ${enterRes.body}`);
    return;
  }

  const enterBody = JSON.parse(enterRes.body);
  console.log(`[VU ${__VU}] 대기열 진입: position=${enterBody.position}`);

  // ── 2단계: 결제 세션 대기 (스케줄러가 5초마다 10명 처리) ────────
  const deadline = Date.now() + MAX_WAIT_SECONDS * 1000;
  let isActive  = false;
  let bookingId = null;

  while (Date.now() < deadline) {
    sleep(POLL_INTERVAL_SEC);

    const statusRes = http.get(
      `${BASE_URL}/api/queue/status/${EVENT_ID}`,
      { headers: authHeaders(user.token) }
    );

    if (statusRes.status !== 200) {
      // NOT_IN_QUEUE(에러) = 대기열 자체가 없어짐 → 더 기다릴 필요 없음
      if (statusRes.status === 400 || statusRes.status === 404) {
        console.log(`[VU ${__VU}] 대기열 없음 (에러 ${statusRes.status}) - 폴링 중단`);
        break;
      }
      continue;
    }

    const status = JSON.parse(statusRes.body);

    if (status.isActive) {
      isActive  = true;
      bookingId = status.bookingId;
      console.log(`[VU ${__VU}] 결제 세션 발급! bookingId=${bookingId}`);
      break;
    }

    console.log(`[VU ${__VU}] 대기 중: position=${status.position}/${status.totalWaiting}`);
  }

  // ── 3단계: 결과 기록 ──────────────────────────────────────────
  if (isActive && bookingId) {
    gotBooking.add(1);
  } else {
    noBooking.add(1);
    console.log(`[VU ${__VU}] 예약 세션 미발급 (티켓 소진 또는 타임아웃 - 정상)`);
  }
}

export function teardown() {
  console.log('');
  console.log('====================================================');
  console.log('  [Race Condition 검증]');
  console.log(`  - got_booking 메트릭 확인: 50 이하여야 정상`);
  console.log(`  - k6 summary의 got_booking count를 확인하세요`);
  console.log('====================================================');
}

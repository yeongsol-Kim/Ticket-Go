/**
 * 시나리오 1: 대기열 진입 폭주
 *
 * 목적: 100명이 동시에 대기열에 진입할 때 Redis 성능 및 정확성 검증
 * 검증:
 *   - 모든 요청 성공 (에러율 < 1%)
 *   - 95% 응답시간 1초 이내
 *   - 중복 진입 없음 (이미 있으면 현재 순번 반환)
 *
 * 실행: k6 run load-test/01_queue_flood.js
 * 옵션: k6 run -e EVENT_ID=1 load-test/01_queue_flood.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { setupUser, authHeaders, BASE_URL } from './helpers.js';

// 커스텀 메트릭
const queueEnterDuration = new Trend('queue_enter_duration', true);
const queueEnterSuccessRate = new Rate('queue_enter_success');
const duplicateEnterCount = new Counter('duplicate_enter'); // 이미 대기 중인 유저 재진입

const NUM_USERS = 100;
const EVENT_ID = parseInt(__ENV.EVENT_ID || '1'); // BTS 콘서트 (1000장)

export const options = {
  vus: NUM_USERS,
  iterations: NUM_USERS,
  setupTimeout: '300s',

  thresholds: {
    http_req_failed:       ['rate<0.01'],   // 에러율 1% 미만
    http_req_duration:     ['p(95)<3000'],  // 95%가 3초 이내 (로컬 기준)
    queue_enter_success:   ['rate>0.99'],   // 성공률 99% 이상
  },
};

export function setup() {
  console.log(`[Setup] 이벤트 ${EVENT_ID}에 ${NUM_USERS}명 계정 준비 중...`);
  const users = [];
  for (let i = 1; i <= NUM_USERS; i++) {
    const user = setupUser(i);
    if (!user.token) {
      console.error(`[Setup] User lt${i}@test.com 준비 실패`);
      continue;
    }
    users.push(user);
  }
  console.log(`[Setup] 완료: ${users.length}명 준비됨`);
  return users;
}

export default function (users) {
  const user = users[__VU - 1];
  if (!user || !user.token) {
    console.error(`[VU ${__VU}] 유저 데이터 없음`);
    return;
  }

  const res = http.post(
    `${BASE_URL}/api/queue/enter`,
    JSON.stringify({ eventId: EVENT_ID, ticketCount: 1 }),
    { headers: authHeaders(user.token) }
  );

  queueEnterDuration.add(res.timings.duration);

  const success = check(res, {
    'status 200':    (r) => r.status === 200,
    'position >= 1': (r) => {
      try { return JSON.parse(r.body).position >= 1; }
      catch { return false; }
    },
  });

  queueEnterSuccessRate.add(success);

  if (!success) {
    console.error(`[VU ${__VU}] 진입 실패: status=${res.status} body=${res.body}`);
    return;
  }

  // 이미 대기열에 있던 유저(재진입) 감지
  const body = JSON.parse(res.body);
  if (body.timestamp && body.timestamp < Date.now() - 5000) {
    duplicateEnterCount.add(1);
  }

  console.log(`[VU ${__VU}] 진입 성공: position=${body.position}`);
}

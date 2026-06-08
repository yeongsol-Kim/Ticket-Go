/*
 * 시나리오 0 : k6 학습용 로그인 테스트
 *
 * 목적: k6 시나리오를 직접 작성해 로그인 테스트 검증
 * 검증:
 *   - 모든 요청 성공률 100%
 * 실헹: k6 run load-test/00_login_test.js
*/

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './helpers.js';


export let options = {
    vus: 10,
    duration: '10s',
    thresholds: {
        'http_req_failed': ['rate < 0.01']
    }
}

export default function() {
    const url = `${BASE_URL}/api/auth/login`;
    const payload = JSON.stringify({
        email: 'user@test.com',
        password: 'user123'
    });
    const params = {
        headers: {
            'Content-Type' : 'application/json'
        }
    }

    const res = http.post(url, payload, params);

    check(res, {
        'status 200': (r) => r.status === 200,
        'token 존재': (r) => JSON.parse(r.body).token !== undefined
    });

    sleep(1);
}
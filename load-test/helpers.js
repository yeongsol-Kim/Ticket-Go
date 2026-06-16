import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function authHeaders(token) {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  };
}

/**
 * 회원가입 (이미 존재해도 무시 - 재실행 안전)
 */
export function registerUser(index) {
  const email = `lt${index}@test.com`;
  const password = 'password123';

  http.post(
    `${BASE_URL}/api/members`,
    JSON.stringify({
      email,
      password,
      name: `LT${index}`,
      phoneNumber: `010-0000-${String(index).padStart(4, '0')}`,
    }),
    {
      headers: JSON_HEADERS,
      // 201(생성) 또는 409(이미 존재) 모두 성공으로 처리 - http_req_failed 카운트 제외
      responseCallback: http.expectedStatuses(201, 409),
    }
  );

  return { email, password };
}

/**
 * 로그인 → JWT 토큰 반환 (실패 시 null)
 */
export function loginUser(email, password) {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: JSON_HEADERS }
  );
  if (res.status !== 200) {
    console.error(`[login] 실패: email=${email}, status=${res.status}`);
    return null;
  }
  return JSON.parse(res.body).token;
}

/**
 * 회원가입 + 로그인 한 번에
 */
export function setupUser(index) {
  const { email, password } = registerUser(index);
  const token = loginUser(email, password);
  return { email, token, index };
}

/**
 * 어드민 로그인 → JWT 토큰 반환
 */
export function loginAdmin() {
  return loginUser('admin@ticketgo.com', 'admin123');
}

/**
 * 이벤트 티켓 사전 발급 (Pre-issued 방식용)
 */
export function preIssueTickets(adminToken, eventId, count) {
  const res = http.post(
    `${BASE_URL}/api/admin/tickets/pre-issue?eventId=${eventId}&count=${count}`,
    null,
    { headers: authHeaders(adminToken) }
  );
  if (res.status !== 200) {
    console.error(`[preIssue] 실패: status=${res.status} body=${res.body}`);
    return false;
  }
  console.log(`[preIssue] 완료: eventId=${eventId}, count=${count}`);
  return true;
}

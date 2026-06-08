# Ticketgo 부하테스트 (k6)

## 사전 준비

### 1. k6 설치
```bash
brew install k6
```

### 2. 서버 실행
MySQL + Redis가 떠있는 상태에서 앱 실행
```bash
./gradlew bootRun
```
DataInitializer가 자동으로 샘플 이벤트 4개와 테스트 계정을 생성합니다.

---

## 시나리오별 실행

### 시나리오 1: 대기열 진입 폭주
100명이 동시에 대기열에 진입 → Redis 처리 성능 측정

```bash
k6 run load-test/01_queue_flood.js
```

**검증 포인트:**
- 에러율 < 1%
- p95 응답시간 < 1초

---

### 시나리오 2: 재고 Race Condition
50장 한정 이벤트에 100명 동시 진입 → 정확히 50건 이하만 예약되는지 검증

```bash
k6 run load-test/02_race_condition.js
```

> **주의:** Event ID=4 (50장)가 소진되지 않은 상태에서 실행해야 합니다.
> DB를 초기화하거나 `availableTickets`를 리셋한 후 실행하세요.

**검증 포인트:**
- `got_booking` count ≤ 50  ← 이게 핵심! 초과 시 버그
- 실행 완료 후 출력 예시: `got_booking.......: 50`

---

### 시나리오 3: E2E 전체 플로우
20명이 대기열 → 결제까지 전체 플로우 완주 → 성공률 + 처리 시간 측정

```bash
k6 run load-test/03_full_flow.js
```

> 스케줄러가 10명/5초 처리하므로 20명 완주까지 약 10~30초 소요됩니다.

**검증 포인트:**
- `flow_success` rate > 95%
- `payment_duration` p95 < 2초

---

## 환경 변수 옵션

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `BASE_URL` | `http://localhost:8080` | 서버 주소 |
| `EVENT_ID` | 시나리오별 기본값 | 테스트할 이벤트 ID |
| `NUM_USERS` | 시나리오별 기본값 | VU(동시 사용자) 수 |

```bash
# 예시: 다른 서버 주소, 다른 이벤트
k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=1 load-test/01_queue_flood.js
```

---

## 결과 해석

k6 실행 후 터미널에 요약이 출력됩니다:

```
✓ status 200
✓ position >= 1

checks.........................: 100.00% ✓ 200 ✗ 0
data_received..................: 45 kB   ...
http_req_duration..............: avg=23ms  min=5ms   med=18ms  max=210ms p(90)=45ms  p(95)=67ms
queue_enter_success............: 100.00% ✓ 100 ✗ 0
```

- `checks`: 검증 통과율
- `http_req_duration`: 응답시간 분포 (p95가 핵심)
- `got_booking` (시나리오 2): 이 숫자가 50 이하여야 정상

---

## DataInitializer 이벤트 정보

| ID | 이름 | 티켓 수 | 용도 |
|----|------|---------|------|
| 1 | BTS 월드투어 콘서트 | 1,000 | 시나리오 1, 3 |
| 2 | 뮤지컬 오페라의 유령 | 500 | 일반 테스트 |
| 3 | 개그 콘서트 2025 | 300 | 일반 테스트 |
| 4 | 한정판 팬미팅 | **50** | **시나리오 2 (race condition)** |

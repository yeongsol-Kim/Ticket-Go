# Ticketgo - 대용량 트래픽 처리 티케팅 서비스

> 콘서트/공연 선착순 티케팅 시스템. 대량 동시 접속, 동시성 제어, 대기열 관리를 직접 구현하고 k6 부하테스트로 검증한 포트폴리오 프로젝트.

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 3.5.9, Spring Security + JWT |
| Database | MySQL 8.0, Spring Data JPA |
| Cache / Queue | Redis (Sorted Set 기반 대기열) |
| Monitoring | Prometheus, Grafana, Spring Actuator |
| Infra | AWS EC2, Docker Compose |
| CI/CD | GitHub Actions |
| Load Test | k6 |

---

## 시스템 아키텍처

```
[Client]
   │
   ▼
[Spring Boot App : 8080]
   ├── Auth API       (JWT 발급)
   ├── Queue API      (Redis Sorted Set 대기열)
   ├── Booking API    (낙관적 락 + 재고 차감)
   └── Payment API    (결제 요청 / 승인)
   │
   ├── [MySQL 8.0]    예약 / 결제 / 티켓 / 회원 데이터
   └── [Redis 7.0]    대기열, 결제 세션
```

### 배포 구성

```
GitHub → GitHub Actions → Docker Hub → EC2 (t3.small)
                                         └── docker-compose
                                               ├── ticketgo-app
                                               ├── ticketgo-mysql
                                               ├── ticketgo-redis
                                               ├── ticketgo-prometheus
                                               └── ticketgo-grafana
```

---

## 핵심 기능 및 설계

### 1. Redis 대기열 시스템

티켓팅 오픈 시 대량 동시 접속을 순서대로 처리하기 위해 Redis Sorted Set 기반 대기열을 구현했습니다.

```
[사용자] → 대기열 진입 (ZADD queue:{eventId} timestamp memberId)
              ↓
[QueueScheduler] → 5초마다 상위 10명 dequeue → Booking 생성 → 결제 세션 발급
              ↓
[사용자] → 폴링 (GET /api/queue/status/{eventId})
              ↓ isActive=true
[사용자] → 결제 요청 → 결제 승인
```

**핵심 설계 포인트:**
- 스케줄러 기반 순차 처리로 DB 동시 쓰기 부하 분산 (10명/5초)
- 결제 세션 TTL 600초 적용으로 자동 만료 처리
- 중복 진입 방지 (이미 대기 중이면 현재 순번 반환)

### 2. 동시성 제어 - 낙관적 락

```java
@Version
private Integer version;  // Event 엔티티 낙관적 락
```

- 재고 차감 시 `@Version`으로 동시 수정 충돌 감지
- `OptimisticLockException` 발생 시 최대 3회 재시도
- 스케줄러 기반 순차 처리와 결합해 충돌 최소화

### 3. 티켓 발급 방식 비교 구현

두 가지 티켓 발급 방식을 모두 구현하고 성능 비교:

#### 방식 A: 결제 시 INSERT (기본 방식)
```
결제 승인 시 → INSERT INTO tickets (새 행 생성)
```

#### 방식 B: 사전 발급 후 UPDATE
```
이벤트 생성 시 → 티켓 미리 생성 (AVAILABLE)
결제 승인 시  → UPDATE tickets SET booking_id=? (기존 행 수정)
```

→ 200명 규모에서는 유의미한 차이 없음. UPDATE 방식의 비관적 락 overhead가 오히려 소폭 느렸음. 상세 결과는 [시나리오 05](#시나리오-05---insert-vs-update-방식-비교-200-vu) 참고.

---

## 부하테스트 결과 (k6)

모든 테스트는 AWS EC2 t3.small 환경에서 진행.

### 시나리오 01 - 대기열 폭주 (100 VU)

| 지표 | 결과 |
|---|---|
| 성공률 | 100% |
| queue_enter_duration avg | 339ms |
| p(95) | 366ms |
| http_req_failed | 0% |

### 시나리오 02 - Race Condition 검증 (100 VU / 티켓 50장)

| 지표 | 결과 |
|---|---|
| got_booking | **50** (초과 발급 0건) |
| no_booking | 50 |
| http_req_failed | 0% |

→ 낙관적 락 + 스케줄러 순차 처리로 정확히 50건만 예약 성공

### 시나리오 03 - E2E 전체 플로우 (50 VU)

| 지표 | 결과 |
|---|---|
| flow_success | 96% |
| payment_duration p(95) | 977ms |
| full_flow_duration avg | 13.89s |

→ 대기열 진입 → 결제 승인 → 예약 CONFIRMED 전 과정 검증

### 시나리오 04 - 대규모 Race Condition (1000 VU / 티켓 50장)

| 지표 | 결과 |
|---|---|
| got_booking | **50** (초과 발급 0건) |
| no_booking | 950 |
| http_req_failed | 0.03% |

→ 1000명 동시 접속에서도 오버부킹 0건 달성

### 시나리오 05 - INSERT vs UPDATE 방식 비교 (200 VU)

| 방식 | avg | p(95) | 성공률 |
|---|---|---|---|
| INSERT | 175ms | 230ms | 50.5%* |
| UPDATE | 181ms | 225ms | 89.5% |

*INSERT 방식 성공률 저하는 200 VU 동시 접속 시 서버 connection reset 발생 (t3.small 한계)

---

## 실행 방법

### 사전 요구사항
- Java 21
- Docker, Docker Compose

### 로컬 실행

```bash
# 인프라 실행 (MySQL, Redis, Prometheus, Grafana)
docker compose up -d

# 앱 실행
./gradlew bootRun
```

### 부하테스트 실행

```bash
# k6 설치 후
k6 run load-test/01_queue_flood.js
k6 run load-test/02_race_condition.js
k6 run load-test/03_full_flow.js
k6 run -e EVENT_ID=4 load-test/04_race_condition_2.js
k6 run load-test/05_insert_vs_update.js

# EC2 대상 실행
k6 run -e BASE_URL=http://{EC2_IP}:8080 load-test/01_queue_flood.js
```

### 테스트 실행

```bash
./gradlew test
```

---

## API 엔드포인트

### 인증
- `POST /api/auth/login` - 로그인 (JWT 발급)

### 회원
- `POST /api/members` - 회원가입

### 이벤트
- `GET /api/events` - 이벤트 목록
- `GET /api/events/{id}` - 이벤트 상세

### 대기열
- `POST /api/queue/enter` - 대기열 진입
- `GET /api/queue/status/{eventId}` - 대기열 상태 조회

### 예약
- `GET /api/bookings/me` - 내 예약 목록

### 결제
- `POST /api/payments` - 결제 요청
- `POST /api/payments/{id}/approve` - 결제 승인 (INSERT 방식)
- `POST /api/payments/{id}/approve-v2` - 결제 승인 (UPDATE 방식)

---

## 한계점 및 개선 방향

### 현재 환경 한계

단일 EC2 t3.small (2 vCPU, 2GB RAM)에 앱 + MySQL + Redis + Prometheus + Grafana를 모두 올린 구성으로, 200명 동시 접속 시 `connection reset by peer` 에러가 발생했습니다.

**원인 분석:**
- 단일 서버에 모든 컴포넌트가 경합 → CPU/메모리 부족
- HikariCP 커넥션 풀 한계 (최대 20개)
- Tomcat 스레드 풀 소진

### 실제 서비스 적용 시 개선 방향

| 문제 | 해결책 |
|---|---|
| 서버 단일 장애점 | 로드밸런서 + 다중 서버 (Auto Scaling) |
| DB 병목 | RDS로 분리, Read Replica 추가 |
| Redis 단일 장애점 | ElastiCache (Redis Cluster) |
| 대규모 부하 테스트 | k6 Cloud 또는 분산 실행 |
| 커넥션 풀 부족 | PgBouncer 또는 커넥션 풀 튜닝 |

### 코드 레벨 개선 가능 사항

- **대기열 스케줄러**: 현재 5초/10명 고정 → 실시간 부하 기반 동적 조절
- **티켓 발급**: INSERT vs UPDATE 비교에서 유의미한 차이가 없었음 → 더 높은 동시성(1000명+)에서 재검증 필요
- **결제 승인**: 동기 처리 → Kafka 등 메시지 큐를 통한 비동기 처리로 응답시간 개선 가능

---

## CI/CD

GitHub Actions를 통한 자동 배포 파이프라인:

```
코드 push (main)
    ↓
GitHub Actions
    ├── JDK 21 설정
    ├── Gradle bootJar 빌드
    ├── Docker 이미지 빌드
    └── Docker Hub push
         ↓
    EC2 SSH 접속
    ├── docker pull
    └── docker-compose up -d
```

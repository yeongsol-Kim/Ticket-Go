# Ticketgo - 1만 명 동시 접속 티케팅 서비스

> 선착순 티케팅의 대기열·재고·결제 흐름을 직접 구현하고, **AWS t3.small(2 vCPU, 2GB) 한 대**에서 1만 명 동시 접속 SLO를 달성할 때까지 부하테스트로 병목을 하나씩 찾아 고친 프로젝트입니다.

## 결과 요약

**SLO:** 동시 접속 10,000명 · 오버부킹 0 · 결제 승인 p95 < 1초 · 첫 진입부터 마지막 결제 승인까지 120초 이내

| 재고 | 오버부킹 | 승인 p95 | 1초 넘은 승인 | 완주 | 판정 |
|---|---|---|---|---|---|
| 10,000 | 0 | 190 / 185ms | 0 / 0명 | 110.5 / 110.6초 | ✅ |
| 1,000 | 0 | 222 / 202ms | 0 / 0명 | 26.7 / 27.1초 | ✅ |
| 100 | 0 | 344 / 456ms | 0 / 0명 | 17.0 / 17.0초 | ✅ |

(측정 2회, p95는 부하생성기 2대의 원자료를 합친 1만 명 기준. 서버 사양은 처음부터 끝까지 그대로)

| | 2차 | 3차 1편 | 3차 2편 (최종) |
|---|---|---|---|
| 승인 p95 | 9.9초 | 0.82~0.97초 | **0.19초** |
| 완주 (첫 진입 → 마지막 승인) | - | 135초 | **110초** |
| 대기열 폴링 요청 수 | 4.6만 | 3.8만 | 1.7만 |

---

## 병목을 찾아 고친 과정

각 단계는 "측정 → 병목 확인 → 수정 → 같은 조건에서 재측정"으로 진행했습니다. 자세한 과정은 블로그 글에 정리했습니다.

| 단계 | 발견한 병목 / 문제 | 해결 | 결과 |
|---|---|---|---|
| 1차 | SLO 정의, 소규모(~1,000 VU) 동시성 검증 | 낙관적 락 + 스케줄러 순차 입장 | 1,000명이 재고 50장에 몰려도 오버부킹 0 |
| | 대기열에서 꺼낸 뒤 결제 세션 발급 전 틈에 당첨자가 "대기열에 없음"으로 거절됨 | 꺼내기·세션 발급 순서 수정 | 오거절 0 |
| 2차 | 대기열 입장 처리율 천장 44명/s (설정만으로는 SLO의 절반) | 예매 생성·재고 차감을 배치 단위 벌크 처리 | **44 → 약 94명/s** |
| | 벌크 전환 중 영속성 컨텍스트 초기화로 만료 예매의 재고가 무한 복원되는 버그 | `clearAutomatically` 제거, 복원도 원자적 UPDATE | 재고 정합성 회복 |
| | 1만 명 중 8,292명에서 연결이 막힘 (Tomcat `max-connections` 8192 + `accept-count` 100) | 연결 상한 상향 | 1만 명 전원 진입, 다음 병목은 승인 p95 9.9초 |
| 3차 1편 | CPU 98~99% 포화, 요청의 60%가 대기열 폴링 | 서버가 다음 폴링 시점을 알려주는 **적응형 폴링** | 승인 p95 12.9 → 3.7초 |
| | 폴링을 줄이자 입장이 너무 빨라져 결제가 한꺼번에 몰림 | 입장 속도 상한, 가중 ratio, 입장 속도 하한 | 승인 p95 1초 근처 |
| | 폴링 1건 프로파일링: GC가 CPU의 16~22% → JWT API가 요청마다 HTTP 세션 생성 (18만 개 누적, 메모리 부족으로 앱 강제 종료) | `SessionCreationPolicy.STATELESS` | GC 비중 15.8% → 2.1%, 승인 p95 0.8~1.0초 |
| 3차 2편 | 폴링은 "폴링 수 ↔ 입장 통보 지연"을 맞바꿀 뿐 / 전원 SSE는 연결당 ~100KB로 힙 부족 | 차례가 10초 이내인 사람만 SSE로 받는 **폴링/SSE 하이브리드** (연결 상한 1,500) | 폴링 1인 약 1회, 완주 120초 첫 통과 |
| | 입장 알림이 빨라지자 진입 폭주(시작 10~20초) 한가운데서 결제가 몰림 | **입장 시작 지연** 15초 | 1초 넘은 승인 0명, 완주는 오히려 단축 |

---

## 아키텍처

```
                       ┌──────────── 대기 ────────────┐
[Client] ─ POST /api/queue/enter ─▶ Redis Sorted Set (진입 순서)
    │                                      │
    │  예상 대기 > 10초: 적응형 폴링        │  QueueScheduler (0.2초마다 최대 32명)
    │    GET /api/queue/status              │    ├─ 대기열에서 꺼냄
    │    ← nextPollSeconds, streamRecommended│    ├─ 예매 일괄 생성 + 재고 원자적 차감 (MySQL)
    │                                      │    ├─ 결제 세션 발급 (Redis, TTL 600초)
    │  예상 대기 ≤ 10초: SSE 구독           │    └─ SSE 구독자에게 admitted 전송 (별도 스레드)
    │    GET /api/queue/stream  ◀──────────┘
    │
    └─ 입장 후: POST /api/payments → POST /api/payments/{id}/approve (티켓 발급)
```

### 핵심 설계

**오버부킹 방지: 조건부 원자적 UPDATE.** 재고 차감은 `UPDATE ... SET available = available - n WHERE id = ? AND available >= n` 한 문장으로 합니다. 영향받은 행이 0이면 매진입니다. 복원(취소·만료)도 원자적 UPDATE라 차감과 복원이 같은 행 락으로 직렬화되고, 별도 락 없이 lost update가 생기지 않습니다.

**대기열: Redis Sorted Set + 스케줄러 배치 입장.** 진입 시각을 score로 저장해 순서를 보장합니다. 스케줄러가 정해진 속도로만 입장시켜, 결제 요청이 한꺼번에 몰리지 않게 합니다.

**적응형 폴링.** 응답에 `nextPollSeconds = clamp(예상 대기 × ratio, 최소, 최대)`를 실어 보냅니다. 예상 대기는 순번을 최근 10초 입장 속도로 나눠 구합니다. 차례가 먼 사람은 길게, 가까운 사람은 짧게 쉽니다.

**폴링/SSE 하이브리드.** 예상 대기가 10초 이하가 되면 응답에 `streamRecommended=true`를 주고, 클라이언트가 SSE로 갈아탑니다. 입장 즉시 `admitted` 이벤트를 받아 통보 지연이 평균 약 0.6ms입니다. Tomcat 서블릿 SSE는 연결당 약 100KB를 써서, 동시 연결을 1,500개로 제한하고 넘으면 폴링을 유지하게 합니다.
- 구독 등록을 상태 조회보다 먼저 해서, 그 사이에 입장한 사람이 알림을 놓치지 않습니다.
- 알림 전송은 별도 스레드에서 해서, 느린 클라이언트가 스케줄러를 막지 않습니다.

**입장 시작 지연.** 대기열이 열린 뒤 15초 동안은 줄만 세우고 입장시키지 않습니다. 1만 명의 진입 요청이 몰리는 구간과 결제가 겹치지 않게 하려는 것입니다.

---

## 측정 방법

- **환경:** 앱 서버 t3.small 한 대(앱·MySQL·Redis 컨테이너), 같은 VPC의 부하생성기 EC2 2대(각 5,000 VU)
- **시나리오:** `load-test/06_throughput.js`(폴링), `load-test/07_sse.js`(하이브리드). 진입 → 대기 → 결제 요청 → 결제 승인
- **판정:** 오버부킹은 DB 확정 건수와 재고로, 완주는 DB의 첫 진입·마지막 승인 시각으로, p95는 두 부하생성기의 승인 원자료(`RAW_LOG`)를 합쳐 1만 명 기준으로 계산
- **원칙:** 회차마다 재고·Redis 초기화, 워밍업 5회 후 측정 2회. 인스턴스를 껐다 켜면 같은 설정도 12~25% 달라져서, 비교는 같은 날 같은 배치 안에서만 했습니다.

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 3.5.9, Spring Security + JWT, SSE (`SseEmitter`) |
| Database | MySQL 8.0, Spring Data JPA |
| Cache / Queue | Redis 7 (Sorted Set 대기열, 결제 세션) |
| Monitoring | Spring Actuator, Micrometer, Prometheus, Grafana |
| Profiling | async-profiler |
| Load Test | k6 (SSE는 xk6-sse 확장 빌드), 분산 부하생성기 2대 |
| Infra | AWS EC2, Docker Compose, GitHub Actions |

---

## 블로그

1. 1차 부하테스트: SLO 정의와 첫 측정
2. INSERT vs UPDATE 티켓 발급 비교 (통제되지 않은 실험이 거짓말을 한다)
3. 대기열 dequeue ↔ 세션 발급 race
4. 처리량 천장과 측정 방법론
5. 벌크 처리와 무한 복원 버그
6. 1만 동시 접속: 연결 천장 8,292와 하드웨어 한계
7. 3차 1편: 적응형 폴링과 세션 버그
8. 3차 2편: 폴링/SSE 하이브리드와 입장 시작 지연

블로그: https://velog.io/@0_s0l

---

## API

| 분류 | 엔드포인트 |
|---|---|
| 인증 | `POST /api/auth/login` (JWT 발급) |
| 회원 | `POST /api/members` |
| 이벤트 | `GET /api/events`, `GET /api/events/{id}` |
| 대기열 | `POST /api/queue/enter`, `GET /api/queue/status/{eventId}`, `GET /api/queue/stream/{eventId}` (SSE) |
| 예약 | `GET /api/bookings/me` |
| 결제 | `POST /api/payments`, `POST /api/payments/{id}/approve` |

Swagger UI: `/swagger-ui/index.html`

---

## 실행 방법

### 사전 요구사항
- Java 21
- MySQL 8.0, Redis 7 (로컬 접속 정보는 `application-dev.yml`에 둡니다. 비밀번호가 들어가 저장소에는 없습니다)

```bash
./gradlew bootRun     # 앱 실행 (기본 프로필 dev)
./gradlew test        # 테스트 (통합 테스트는 Testcontainers를 쓰므로 Docker 필요)
```

운영 프로필(`prod`)은 `JWT_SECRET`, `DB_*` 환경변수가 없으면 기동하지 않습니다.

### 부하테스트

```bash
./load-test/seed_users.sh                       # 테스트 회원 생성 + tokens.json 발급 (토큰 유효기간 24시간)
k6 run -e BASE_URL=http://{HOST}:8080 -e STOCK=10000 -e RAW_LOG=1 load-test/06_throughput.js
./k6-sse run -e BASE_URL=http://{HOST}:8080 -e STOCK=10000 -e RAW_LOG=1 load-test/07_sse.js
```

`k6-sse`는 `xk6 build v2.0.0 --with github.com/phymbert/xk6-sse --output k6-sse`로 빌드한 k6입니다. 시나리오별 설명은 [`load-test/README.md`](load-test/README.md)에 있습니다.

---

## 한계와 다음 과제

- **매진 통보 없음.** 매진 뒤에도 대기자에게 알리지 않아, 표를 못 산 사람이 최대 대기 시간까지 기다리고 그동안 폴링이 계속 들어옵니다. 매진 응답·SSE 매진 이벤트·대기열 정리가 필요합니다.
- **콜드 스타트.** 재시작 직후 첫 회차는 입장 처리율이 약 40명/s로, 워밍업 후의 절반 이하입니다. 결과는 모두 워밍업한 서버 기준입니다.
- **한 서버에 앱·MySQL·Redis.** CPU 200% 중 MySQL이 약 35%를 쓰고, 2GB 메모리를 셋이 나눠 씁니다. 분리하면 앱 자원은 늘지만 DB·Redis 왕복에 네트워크 지연이 붙어 다시 측정해야 합니다.
- **앱 서버 한 대 전제.** SSE 구독 목록이 앱 메모리에 있어, 여러 대로 늘리면 Redis pub/sub 등으로 입장 이벤트를 공유해야 합니다.

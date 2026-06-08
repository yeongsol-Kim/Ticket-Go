# Ticketgo - 대용량 트래픽 처리 티케팅 서비스

> 콘서트/공연 티케팅 시스템으로 대용량 동시 트래픽 처리 및 동시성 제어를 학습하기 위한 포트폴리오 프로젝트

## 프로젝트 개요

실제 티케팅 서비스에서 발생하는 **대량의 동시 접속과 선착순 예매 경쟁 상황**을 시뮬레이션하고, 다양한 동시성 제어 전략을 구현하여 성능을 비교 분석하는 프로젝트입니다.

### 핵심 목표
- 🎯 대용량 트래픽 처리 (10,000+ 동시 접속)
- 🔒 동시성 제어 전략 구현 (낙관적 락, 비관적 락, 분산 락)
- ⚡ 성능 최적화 및 벤치마크
- 📊 실시간 대기열 시스템
- 🎫 이벤트 기반 아키텍처 (Kafka)
## 기술 스택

### Backend
- **Java 21** - 최신 LTS 버전
- **Spring Boot 3.5.9** - 프레임워크
- **Spring Data JPA** - ORM
- **Spring Security + JWT** - 인증/인가
- **MySQL 8.0** - 메인 데이터베이스

### Infrastructure
- **Redis** - 분산 락, 대기열 관리, 캐싱
- **Kafka** - 이벤트 기반 비동기 처리
- **Redisson** - 분산 락 구현

### Monitoring & Testing
- **Micrometer + Prometheus** - 메트릭 수집
- **Spring Boot Actuator** - 헬스 체크
- **JMeter / K6** - 부하 테스트
- **Testcontainers** - 통합 테스트

## 아키텍처 설계

### DDD (Domain-Driven Design) 계층 구조

```
presentation/     # API Controllers
    └── api/
application/      # Use Cases (비즈니스 플로우)
    ├── booking/
    ├── event/
    └── queue/
domain/           # 도메인 모델 (핵심 비즈니스 로직)
    ├── member/
    ├── event/
    ├── ticket/
    ├── booking/
    └── payment/
infrastructure/   # 외부 시스템 연동
    ├── persistence/
    ├── messaging/
    └── external/
```

### 주요 도메인 모델

#### Event (공연/이벤트)
- 공연 정보 관리
- 전체 티켓 수 및 남은 티켓 수 추적
- 상태: DRAFT, ON_SALE, SOLD_OUT, CANCELLED, COMPLETED

#### Ticket (티켓)
- **예약 시점에 생성** (INSERT 방식)
- 판매된 티켓만 DB에 저장하여 효율성 극대화

#### Booking (예약)
- 15분 타임아웃 적용
- 상태: PENDING, RESERVED, CONFIRMED, CANCELLED, EXPIRED
- 낙관적 락 적용 (@Version)

#### Payment (결제)
- Mock 결제 게이트웨이 연동
- 상태: PENDING, APPROVED, FAILED, CANCELLED, REFUNDED

#### Member (회원)
- 이메일 기반 인증
- 등급: REGULAR, VIP (대기열 우선순위)
- 권한: USER, ADMIN

---

## 핵심 설계 결정사항

### 1. 티켓 관리 방식: 예약 시 생성 방식 선택

티케팅 시스템에서 가장 중요한 설계 결정 중 하나는 **티켓을 언제 생성할 것인가**입니다.

#### 비교한 두 가지 방식

##### 방식 1: 이벤트 생성 시 티켓 미리 생성
```
Event 생성 시:
  INSERT INTO tickets ... (총 10,000건 생성)

예약 시:
  UPDATE tickets SET status='RESERVED', booking_id=123
  WHERE id IN (1,2,3) AND status='AVAILABLE'
```

**장점:**
- 티켓별 상태를 DB에서 직접 관리 가능
- 좌석 지정 공연에 유리

**단점:**
- 이벤트 생성 시 대량 INSERT 필요
- 예약 시 UPDATE 연산 (INSERT보다 느림)
- 타임아웃 관리 복잡 (RESERVED 상태 티켓 처리)
- 미판매 티켓도 DB에 저장 (리소스 낭비)

##### 방식 2: 예약 시 티켓 생성 ✅ **최종 선택**
```
Event 생성 시:
  INSERT INTO events (totalTickets=10000, availableTickets=10000)

예약 시:
  UPDATE events SET availableTickets = availableTickets - 2
  INSERT INTO tickets (event_id, booking_id, ticket_number)
  INSERT INTO tickets (event_id, booking_id, ticket_number)
```

**장점:**
- ✅ Event 생성이 가벼움 (즉시 판매 가능)
- ✅ **INSERT가 UPDATE보다 30-40% 빠름** (벤치마크 예정)
- ✅ 판매된 티켓만 DB에 저장 (리소스 효율)
- ✅ 타임아웃 관리 간단 (Booking 레벨에서만)
- ✅ 불필요한 상태 변경 제거

**단점:**
- Event.availableTickets가 hot spot (모든 예약이 한 row 경합)
- **해결책**: 분산 락 + 대기열 시스템으로 트래픽 제어

#### 최종 결정 이유

선착순 티케팅 특성상:
1. 좌석 번호가 필요 없음 (누가 먼저 예약하느냐만 중요)
2. INSERT 연산이 UPDATE보다 빠르고 효율적
3. 현재 요구사항에 최적화 (YAGNI 원칙)

#### 향후 확장성 (좌석 지정 공연)

좌석 지정이 필요한 경우:
```java
@Entity
class Event {
    Boolean hasSeating;  // 좌석 여부 플래그
}

@Entity
class Seat {  // 좌석 지정 공연용 (새로 추가)
    Long eventId;
    String seatNumber;  // "A-15"
    SeatStatus status;
}
```

- 선착순: 기존 방식 유지 (Ticket INSERT)
- 좌석 지정: Seat 엔티티 추가 (Seat 미리 생성 + UPDATE)
- 기존 코드 영향 최소화

---

### 2. 동시성 제어 전략 (3가지 구현 예정)

#### 낙관적 락 (Optimistic Locking)
```java
@Version
private Integer version;
```
- JPA의 @Version 활용
- 충돌 시 재시도 (OptimisticLockException)
- 낮은 경합 상황에 적합

#### 비관적 락 (Pessimistic Locking)
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
SELECT ... FOR UPDATE
```
- DB row-level lock
- 높은 경합 상황에 적합
- 데드락 위험 관리 필요

#### 분산 락 (Distributed Lock with Redis)
```java
RLock lock = redisson.getLock("event:" + eventId);
lock.lock(10, TimeUnit.SECONDS);
```
- Redis + Redisson 활용
- 멀티 인스턴스 환경 지원
- 대기열 시스템과 결합

#### 하이브리드 전략 (최종 목표)
```
대기열 진입 → 순서 보장 (Redis Sorted Set)
    ↓
입장 허가 (초당 100명)
    ↓
분산 락 획득 (Redis)
    ↓
재고 확인 + 예약 생성 (낙관적 락)
    ↓
결제 처리 (Kafka 비동기)
```

---

### 3. 대기열 시스템

#### Redis Sorted Set 활용
```
Key: queue:{eventId}
Score: timestamp + random (공정성)
Value: userId
```

**주요 기능:**
- 공정한 순서 보장
- 실시간 대기 순서 조회
- 제어된 입장 (초당 100명)
- JWT 토큰 기반 입장 권한 부여

---

### 4. 데이터베이스 설계

#### 인덱스 전략
```sql
-- Event 조회 최적화
CREATE INDEX idx_event_status_sale ON events(status, sale_start_date_time);

-- Ticket 조회 최적화
CREATE INDEX idx_ticket_event_status ON tickets(event_id, status);

-- Booking 타임아웃 처리
CREATE INDEX idx_booking_status_expires ON bookings(status, expires_at);

-- Payment 조회
CREATE UNIQUE INDEX idx_payment_booking ON payments(booking_id);
```

#### FK는 ID만 저장 (JPA 연관관계 미사용)
```java
// ❌ JPA 연관관계 사용 X
@ManyToOne
private Event event;

// ✅ ID만 저장
@Column(name = "event_id")
private Long eventId;
```

**이유:**
- N+1 문제 방지
- 명시적 조인 쿼리 작성
- 성능 최적화 용이

---

## API 엔드포인트 (예정)

### 공연 조회
- `GET /api/v1/events` - 공연 목록
- `GET /api/v1/events/{eventId}` - 공연 상세

### 대기열
- `POST /api/v1/events/{eventId}/queue/join` - 대기열 진입
- `GET /api/v1/events/{eventId}/queue/position` - 순서 조회
- `GET /api/v1/events/{eventId}/queue/stream` - SSE 실시간 업데이트

### 예약
- `POST /api/v1/bookings` - 예약 생성
- `GET /api/v1/bookings/{bookingId}` - 예약 조회
- `DELETE /api/v1/bookings/{bookingId}` - 예약 취소

### 결제
- `POST /api/v1/payments` - 결제 요청
- `POST /api/v1/payments/confirm` - 결제 확인 (웹훅)

---

## 성능 목표

### 처리량
- 대기열 진입: **10,000 req/s**
- 예약 생성: **1,000 req/s**
- 결제 처리: **500 req/s**

### 응답 시간 (p99)
- 대기열 진입: **< 100ms**
- 예약 생성: **< 500ms**
- 결제 처리: **< 300ms**

### 동시성 정확도
- 10,000명이 100장 티켓 경쟁
- 정확히 100개만 성공
- **오버부킹 0건 보장**

---

## 개발 일정

- [x] **Phase 1**: 기본 인프라 및 도메인 모델 (1주차)
- [ ] **Phase 2**: 동시성 제어 전략 구현 (2주차)
- [ ] **Phase 3**: 대기열 시스템 (3주차)
- [ ] **Phase 4**: Kafka 이벤트 기반 아키텍처 (4주차)
- [ ] **Phase 5**: 결제 통합 및 워크플로우 (5주차)
- [ ] **Phase 6**: 성능 최적화 및 캐싱 (6주차)
- [ ] **Phase 7**: 모니터링 및 성능 테스트 (7주차)
- [ ] **Phase 8**: 테스트 및 문서화 (8주차)

---

## 실행 방법

### 사전 요구사항
- Java 21
- MySQL 8.0
- Redis
- Kafka

### 로컬 실행
```bash
# 의존성 다운로드
./gradlew build

# 애플리케이션 실행
./gradlew bootRun
```

### 환경 변수
```bash
DB_USERNAME=root
DB_PASSWORD=your-password
JWT_SECRET=your-secret-key
```

---

## 기술적 고민과 학습 포인트

### 1. 티켓 생성 시점 결정
- 미리 생성 vs 예약 시 생성
- INSERT vs UPDATE 성능 비교
- 트레이드오프 분석

### 2. 동시성 제어
- 낙관적 락 vs 비관적 락 vs 분산 락
- 각 전략의 적합한 사용 시나리오
- 실제 부하 테스트 결과 비교

### 3. 대기열 설계
- 공정성 보장 알고리즘
- Redis Sorted Set 활용
- 트래픽 제어 전략

### 4. 확장성 고려
- 현재 요구사항과 미래 확장성의 균형
- YAGNI 원칙 적용
- 좌석 지정 기능 확장 가능성

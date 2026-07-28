# 대기열 입장 벌크 처리 설계 (A+B+D)

> 목표: 스케줄러의 건별 순차 처리(23ms/명, 실측 천장 44/s)를 벌크로 바꿔 SLO 83/s에 접근.
> 범위: **A(트랜잭션 1개) + B(재고 UPDATE 1번) + D(Redis 파이프라이닝)**. C(INSERT 배치)는 보류.

---

## 0. "배치가 어디서 얼마 동안 모아서 처리하나" — 가장 헷갈리는 부분

배치라는 말이 **두 개의 다른 층위**에서 쓰인다. 이걸 구분하는 게 이 문서의 핵심.

### 층위 1: "요청을 모으는" 배치 — 이미 우리가 하고 있다

> Q. 어디서 얼마 동안의 요청을 모으나?
> A. **Redis 큐에서, 스케줄러 주기(delay)만큼.** 그리고 이건 새로 만드는 게 아니라 **지금도 이미 이렇게 동작 중**이다.

우리 서비스는 사용자 요청을 그 자리에서 처리하지 않는다. 흐름이 이렇다:

```
[사용자 1000명이 아무 때나 랜덤하게 몰려옴]
        │  각자 POST /api/queue/enter
        ▼
[Redis ZSet 큐]  ← 여기에 "쌓인다" (이게 모으는 곳)
        │
        ▼
[스케줄러가 N초마다 깨어나 상위 200명을 한 번에 꺼냄]  ← 이게 "배치를 뜨는" 순간
        │  peekNext(eventId, 200)
        ▼
[꺼낸 200명을 처리]  ← 지금은 여기서 한 명씩 for 루프 (느림)
```

**즉 "얼마 동안 모으나"의 답 = 스케줄러 주기(delay-ms).**
- delay=1000이면 "직전 사이클 끝난 뒤 1초 동안 큐에 쌓인 사람들"이 다음 배치가 된다.
- 사용자 요청이 랜덤하게 와도 상관없다. 큐가 그 랜덤성을 흡수한다. 스케줄러는 그저 "지금 큐 상위 200명"을 가져올 뿐.

**이 층위는 코드를 안 바꾼다.** 이미 `peekNext(eventId, admitCount)`로 200명을 모으고 있다.
바꾸는 건 **모은 다음** — 그 200명을 "한 명씩" 처리하던 걸 "한 번에" 처리하는 부분이다.

### 층위 2: "모은 200건을 DB에 어떻게 보내나" — 여기가 최적화 대상

200명을 모은 건 같은데, 지금은 DB에 이렇게 보낸다:

```
for (200명) {
  트랜잭션 시작 → SELECT → UPDATE → INSERT → 커밋   // 200번 반복
}
```

이걸 이렇게 바꾼다:

```
트랜잭션 시작
  UPDATE 재고 (한 번, 200명분 한꺼번에)
  INSERT booking 200건
커밋 (한 번)
```

**"자동으로 해주나?"** — 부분적으로.
- **A(트랜잭션 1개), B(재고 UPDATE 1번)**: 우리가 **코드로 직접** 구조를 바꿔야 한다. 자동 아님.
- **INSERT를 물리적으로 묶는 것**(C): `hibernate.jdbc.batch_size` 설정을 켜면 Hibernate가 **반자동**으로 묶어준다. 단 IDENTITY 때문에 지금은 안 먹음(C 보류 이유).

정리:
| 층위 | 무엇 | 누가 하나 | 우리가 바꾸나 |
|---|---|---|---|
| 1. 요청 모으기 | Redis 큐 + 스케줄러 주기 | 이미 동작 중 | ❌ 그대로 |
| 2. 처리 구조 | 트랜잭션/재고UPDATE | **우리가 코드로** | ✅ A, B |
| 2. INSERT 물리 배치 | JDBC batch | Hibernate 반자동(설정) | ⏸ C 보류 |

---

## 1. 현재 코드 (Before) — 건별 순차

`QueueScheduler.createBookingAndIssueSession()` → 유저 1명당:

```java
@Transactional                                   // 유저마다 트랜잭션 = 커밋 200번
public Booking createBooking(...) {
    Event event = eventRepository.findById(eventId)...;  // SELECT (같은 행 200번)
    event.decreaseAvailableTickets(ticketCount);         // UPDATE (같은 행 200번 순차)
    booking = bookingRepository.save(booking);           // INSERT (1건)
}
// + queueService.issuePaymentSession()  → Redis SET  (200번)
// + queueService.removeFromQueue()      → Redis ZREM (200번)
```

**200명 = 커밋 200 + SELECT 200 + UPDATE 200 + INSERT 200 + Redis 왕복 400.**
실측: 23ms/명 → 천장 44/s (③ 200/200), 더 밀면(delay=0) 앱 크래시.

---

## 2. 목표 구조 (After) — A+B+D

```java
// 스케줄러: 배치 단위로 한 번에
@Transactional                                   // ── A: 배치 전체가 트랜잭션 1개
public BulkResult admitBatch(Long eventId, List<QueuedUser> users) {
    // ── B: 재고를 배치 총합만큼 한 번의 조건부 UPDATE로 차감
    int want = users.stream().mapToInt(QueuedUser::ticketCount).sum();
    int available = eventRepository.findAvailableTickets(eventId);   // SELECT 1번
    int batch = Math.min(want, available);                          // 재고에 맞춰 자르기
    if (batch == 0) { /* SOLD_OUT */ return BulkResult.empty(); }

    int affected = eventRepository.decreaseStock(eventId, batch);   // UPDATE 1번, 조건부
    if (affected == 0) {
        // 그 사이 재고가 변함(취소/만료 복원 등) → 재조회 후 재시도 or 다음 사이클로
        return BulkResult.retry();
    }

    // booking 생성 (INSERT는 아직 건별 — C 보류. 단 같은 트랜잭션 안이라 커밋은 1번)
    List<Booking> bookings = users.stream()
        .limit(batchUserCount)                    // 재고만큼만
        .map(u -> bookingRepository.save(build(u)))
        .toList();

    return BulkResult.of(bookings);
}
// ── D: Redis 세션발급 + 큐제거를 파이프라인으로 1~2왕복에
queueService.issueSessionsAndRemove(eventId, bookings);  // executePipelined
```

### A — 트랜잭션 1개
- `@Transactional`을 **배치 단위**로. 커밋 200 → 1. fsync 200 → 1. (가장 큰 이득 예상)
- 부작용 주의: 트랜잭션이 길어지면 그동안 DB 커넥션 1개를 오래 점유. 배치 크기 상한 필요.

### B — 재고 조건부 UPDATE (동시성 + 경계 안전)
```sql
UPDATE events
SET available_tickets = available_tickets - :batch
WHERE id = :id AND available_tickets >= :batch
```
- 반환값(영향 행 수) 1 = 성공, 0 = "그 사이 재고 변함" → 재조회 후 재시도.
- **오버부킹을 DB가 원자적으로 막는다.** @Version 불필요(이 경로엔).
  → "@Version의 진짜 자리는 복원 경로(취소/만료)"라는 결론이 코드로 확정됨.

**경계 케이스 (재고 60인데 200명):**
1. `available=60`, `want=200` → `batch = min(200,60) = 60`
2. 앞 60명만 booking, 나머지 140명은 큐에 남김 (다음 사이클엔 재고 0 → SOLD_OUT)
3. → 정확히 60명 처리, 오버부킹 0, 팔 수 있는 건 다 팜

**동시성 케이스 (읽은 뒤 누가 재고를 건드림):**
- 5번 UPDATE의 `WHERE available >= batch`가 **실행 시점 최신값**으로 재검증.
- 재고가 늘었으면(만료 복원) OK, 줄었으면 affected=0 → 재조회+재clamp+재시도.
- 즉 "읽은 값을 믿지 않고, 뺄 때 다시 확인"하는 낙관적 제어.

### D — Redis 파이프라이닝
- 지금: `issuePaymentSession`(SET) 200번 + `removeFromQueue`(ZREM) 200번 = 400 왕복.
- 파이프라인: 명령을 모아 1~2 왕복에 전송.
```java
redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
    for (Booking b : bookings) {
        conn.set(sessionKey(b), ...);    // 세션 발급
        conn.zRem(queueKey, memberId);   // 큐 제거
    }
    return null;
});
```

---

## 3. 안전 로직 (재고 경계 + 동시성)

```
1. peek N명 (admit-count, 예: 200)
2. SELECT available_tickets
3. batch = min(요청합, available)             ← 재고 초과 방지 (경계)
4. if batch == 0 → SOLD_OUT, 종료
5. UPDATE ... available - batch WHERE available >= batch   ← 뺄 때 재검증 (동시성)
6. affected == 0 → 2로 (그 사이 재고 변함, 재시도)
   affected == 1 → batch명 booking INSERT + Redis 파이프라인
7. 세션 발급 성공분만 removeFromQueue (기존 race 방지 로직 유지)
```

---

## 4. 롤백/실패 안전성

- **A로 트랜잭션이 1개가 되면서 "부분 성공"이 사라진다.** 배치 중 하나가 실패하면 전체 롤백.
  - 재고는 3번에서 이미 clamp했으므로 재고 부족으로 인한 중간 실패는 없음.
  - INSERT 실패(DB 오류 등)는 전체 롤백 → 큐에서 안 지워짐 → 다음 사이클 재시도. (기존과 동일한 복원력)
- **Redis(D)는 트랜잭션 밖.** DB 커밋 성공 후 Redis 실행. Redis 실패 시 세션 미발급 → 유저는 다음 사이클 재시도(큐에 남아있음). 기존 "세션 발급 성공분만 큐 제거" 로직과 정합.

---

## 5. 측정 계획

| 단계 | 무엇 | 기대 |
|---|---|---|
| Before | ③ 200/200 재확인 | 44/s (기준선) |
| +A | 트랜잭션 1개만 | 커밋 병목 제거 → ? |
| +A+B | 재고 UPDATE도 1번 | ? |
| +A+B+D | Redis도 파이프라인 | ? |

- 각 단계 개별 측정 → **어느 최적화가 얼마를 벌었는지 분리**.
- 측정법: `measure.sh` (Prometheus 카운터 델타, 워밍업 후 본측정).
- 검증: 매 단계 오버부킹 0 (DB `available_tickets` 확인), 성공률, 승인 p95.

---

## 6. 보류: C (INSERT 배치)

- `Booking`이 `@GeneratedValue(IDENTITY)` → Hibernate가 ID 받으려 INSERT 즉시 실행 → 배치 불가.
- `batch_size=20`이 설정돼 있으나 **IDENTITY 때문에 무효**(그 자체로 소재).
- A+B+D 측정 후 부족하면 착수. ID 전략(JDBC 직접 vs SEQUENCE/TABLE)은 그때 결정.
```

package com.yeongsol.ticketgo.domain.queue.scheduler;

import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 대기열 스케줄러
 * 5초마다 대기열 상위 N명을 퇴장시키고 Booking 생성 + 결제 세션 발급
 * Booking 생성 시점을 스케줄러로 제어해 재고 차감 부하를 10명/5초로 분산
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final QueueService queueService;
    private final EventRepository eventRepository;
    private final BookingService bookingService;
    private final MeterRegistry meterRegistry;   // 2차 처리량 측정용 (Prometheus)

    // 처리율 sweep용 config (재컴파일 없이 env/properties로 조절)
    // env 예: QUEUE_SCHEDULER_ADMIT_COUNT=50, QUEUE_SCHEDULER_DELAY_MS=1000
    @Value("${queue.scheduler.admit-count:10}")
    private int admitCountPerCycle;      // 1회 입장시킬 최대 인원

    @Value("${queue.scheduler.max-booking-retry:3}")
    private int maxBookingRetry;         // 낙관락 리트라이 횟수

    @Scheduled(
            fixedDelayString = "${queue.scheduler.delay-ms:5000}",
            initialDelayString = "${queue.scheduler.initial-delay-ms:5000}"
    )
    public void processQueueAdmission() {
        try {
            // 판매 중인 이벤트 조회
            List<Event> onSaleEvents = eventRepository.findByStatus(EventStatus.ON_SALE);
            if (onSaleEvents.isEmpty()) { return; }

            log.info("대기열 자동 입장 처리 시작: 이벤트 수={}", onSaleEvents.size());
            for (Event event : onSaleEvents) {
                try {
                    if (event.getAvailableTickets() == 0) continue;

                    long queueSize = queueService.getQueueSize(event.getId());
                    if (queueSize == 0) continue;

                    // 큐에서 제거하지 않고 읽기만 한다 (제거는 세션 발급 성공 후)
                    List<QueueService.QueuedUser> users =
                            queueService.peekNext(event.getId(), admitCountPerCycle);

                    // ── A: 배치 전체를 트랜잭션 1개로 생성 (커밋 N→1, event UPDATE N→1)
                    List<Booking> bookings = createBookingsBatchWithRetry(event.getId(), users);

                    // ── D: 커밋 성공분의 세션 발급 + 큐 제거를 Redis 파이프라인으로 (왕복 3N→~2)
                    // (실패 시 큐에 남아 다음 사이클 재시도 → dequeue-세션 race 제거)
                    if (!bookings.isEmpty()) {
                        List<QueueService.SessionGrant> grants = bookings.stream()
                                .map(b -> new QueueService.SessionGrant(b.getMemberId(), b.getId()))
                                .toList();
                        queueService.issueSessionsAndRemoveBatch(event.getId(), grants);
                    }

                    log.info("대기열 처리 완료: eventId={}, 처리인원={}, 잔여대기={}",
                            event.getId(), bookings.size(), queueSize - bookings.size());

                } catch (Exception e) {
                    log.error("이벤트 대기열 처리 중 오류: eventId={}", event.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("대기열 스케줄러 실행 중 오류", e);
        }
    }

    /**
     * 배치 예매 생성 (트랜잭션 1개) + OptimisticLockException 시 배치 전체 재시도
     *
     * 재고 차감(event UPDATE)이 복원 경로(취소/만료)와 커밋 시점에 충돌하면 낙관락 예외 →
     * 배치 전체가 롤백되므로 배치를 통째로 재시도한다. Redis 세션 발급은 커밋 성공 후(호출부)라
     * 재시도 중 중복 발급 위험 없음.
     *
     * @return 커밋에 성공해 생성된 Booking 목록 (실패 시 빈 목록)
     */
    private List<Booking> createBookingsBatchWithRetry(Long eventId, List<QueueService.QueuedUser> users) {
        if (users.isEmpty()) return List.of();

        for (int attempt = 1; attempt <= maxBookingRetry; attempt++) {
            try {
                List<Booking> created = bookingService.createBookingsBatch(
                        eventId, users, QueueService.PAYMENT_SESSION_DURATION_SECONDS);
                meterRegistry.counter("queue.admission.issued").increment(created.size());  // 처리량
                return created;

            } catch (OptimisticLockingFailureException e) {
                // JpaTransactionManager가 커밋 시 jakarta OptimisticLockException을
                // Spring ObjectOptimisticLockingFailureException으로 번역 → 그 상위타입으로 포착
                meterRegistry.counter("queue.admission.lock_conflict").increment();  // 낙관락 충돌
                log.warn("배치 재고 차감 충돌 - attempt: {}/{}, eventId={}, 인원={}",
                        attempt, maxBookingRetry, eventId, users.size());
                if (attempt == maxBookingRetry) {
                    meterRegistry.counter("queue.admission.failed").increment(users.size());
                    log.error("배치 예매 생성 실패 (재시도 초과): eventId={}, 인원={}", eventId, users.size());
                }

            } catch (Exception e) {
                meterRegistry.counter("queue.admission.failed").increment(users.size());
                log.error("배치 예매 생성 실패: eventId={}, 인원={}", eventId, users.size(), e);
                return List.of();
            }
        }
        return List.of();
    }
}

package com.yeongsol.ticketgo.domain.queue.scheduler;

import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

                    for (QueueService.QueuedUser user : users) {
                        boolean issued = createBookingAndIssueSession(event.getId(), user);
                        // 세션 발급에 성공한 경우에만 큐에서 제거
                        // (실패 시 큐에 남아 다음 사이클에 재시도 → dequeue-세션 race 제거)
                        if (issued) {
                            queueService.removeFromQueue(event.getId(), user.memberId());
                        }
                    }

                    log.info("대기열 처리 완료: eventId={}, 처리인원={}, 잔여대기={}",
                            event.getId(), users.size(), queueSize - users.size());

                } catch (Exception e) {
                    log.error("이벤트 대기열 처리 중 오류: eventId={}", event.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("대기열 스케줄러 실행 중 오류", e);
        }
    }

    /**
     * Booking 생성 후 결제 세션 발급
     * OptimisticLockException 발생 시 최대 3회 재시도
     *
     * @return 세션 발급 성공 여부 (true면 호출부에서 큐 제거)
     */
    private boolean createBookingAndIssueSession(Long eventId, QueueService.QueuedUser user) {
        for (int attempt = 1; attempt <= maxBookingRetry; attempt++) {
            try {
                Booking booking = bookingService.createBooking(
                        user.memberId(),
                        eventId,
                        user.ticketCount(),
                        QueueService.PAYMENT_SESSION_DURATION_SECONDS
                );
                queueService.issuePaymentSession(eventId, user.memberId(), booking.getId());
                meterRegistry.counter("queue.admission.issued").increment();   // 처리량: 세션 발급 성공
                log.info("예매 생성 및 결제 세션 발급: eventId={}, memberId={}, bookingId={}",
                        eventId, user.memberId(), booking.getId());
                return true;

            } catch (OptimisticLockException e) {
                meterRegistry.counter("queue.admission.lock_conflict").increment();  // 낙관락 충돌 = 락 발동 증거
                log.warn("재고 차감 충돌 - attempt: {}/{}, eventId={}, memberId={}",
                        attempt, maxBookingRetry, eventId, user.memberId());
                if (attempt == maxBookingRetry) {
                    meterRegistry.counter("queue.admission.failed").increment();     // 재시도 초과 실패
                    log.error("예매 생성 실패 (재시도 초과): eventId={}, memberId={}",
                            eventId, user.memberId());
                }

            } catch (Exception e) {
                meterRegistry.counter("queue.admission.failed").increment();         // 기타 실패
                log.error("예매 생성 실패: eventId={}, memberId={}", eventId, user.memberId(), e);
                return false;
            }
        }
        return false;
    }
}

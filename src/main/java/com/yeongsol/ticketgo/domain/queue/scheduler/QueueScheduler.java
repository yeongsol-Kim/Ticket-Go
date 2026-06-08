package com.yeongsol.ticketgo.domain.queue.scheduler;

import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final int ADMIT_COUNT_PER_CYCLE = 10; // 1회 입장시킬 최대 인원
    private static final int MAX_BOOKING_RETRY = 3; // 낙관락 리트라이 횟수

    // 5초에 1번씩 호출
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
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

                    List<QueueService.QueuedUser> users =
                            queueService.dequeueNext(event.getId(), ADMIT_COUNT_PER_CYCLE);

                    for (QueueService.QueuedUser user : users) {
                        createBookingAndIssueSession(event.getId(), user);
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
     */
    private void createBookingAndIssueSession(Long eventId, QueueService.QueuedUser user) {
        for (int attempt = 1; attempt <= MAX_BOOKING_RETRY; attempt++) {
            try {
                Booking booking = bookingService.createBooking(
                        user.memberId(),
                        eventId,
                        user.ticketCount(),
                        QueueService.PAYMENT_SESSION_DURATION_SECONDS
                );
                queueService.issuePaymentSession(eventId, user.memberId(), booking.getId());
                log.info("예매 생성 및 결제 세션 발급: eventId={}, memberId={}, bookingId={}",
                        eventId, user.memberId(), booking.getId());
                return;

            } catch (OptimisticLockException e) {
                log.warn("재고 차감 충돌 - attempt: {}/{}, eventId={}, memberId={}",
                        attempt, MAX_BOOKING_RETRY, eventId, user.memberId());
                if (attempt == MAX_BOOKING_RETRY) {
                    log.error("예매 생성 실패 (재시도 초과): eventId={}, memberId={}",
                            eventId, user.memberId());
                }

            } catch (Exception e) {
                log.error("예매 생성 실패: eventId={}, memberId={}", eventId, user.memberId(), e);
                return;
            }
        }
    }
}

package com.yeongsol.ticketgo.domain.booking.service;

import com.yeongsol.ticketgo.domain.booking.exception.BookingNotFoundException;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.model.BookingStatus;
import com.yeongsol.ticketgo.domain.booking.repository.BookingRepository;
import com.yeongsol.ticketgo.domain.event.exception.EventNotFoundException;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;

    /**
     * 티켓 예약 (핵심 로직!)
     * 1. Event 재고 차감 (낙관적 락)
     * 2. Booking 생성 (RESERVED) - 결제 세션 남은 시간을 타임아웃으로 사용
     */
    @Transactional
    public Booking createBooking(Long memberId, Long eventId, int ticketCount, long timeoutSeconds) {
        // 1. Event 조회 및 재고 차감 (낙관적 락 - @Version)
        Event event = eventRepository.findById(eventId)
                .orElseThrow(EventNotFoundException::new);

        // 재고 차감 (동시성 제어 핵심!)
        // OptimisticLockException 발생 시 재시도 필요 (상위에서 처리)
        event.decreaseAvailableTickets(ticketCount);

        // 2. Booking 생성 (바로 RESERVED 상태)
        Booking booking = Booking.builder()
                .memberId(memberId)
                .eventId(eventId)
                .bookingNumber(UUID.randomUUID().toString())
                .ticketCount(ticketCount)
                .totalAmount(event.getPrice().intValue() * ticketCount)
                .timeoutSeconds(timeoutSeconds)
                .build();

        booking = bookingRepository.save(booking);

        log.info("예약 생성 완료 - bookingId: {}, eventId: {}, ticketCount: {}",
                booking.getId(), eventId, ticketCount);

        return booking;
    }

    /**
     * 예약 번호로 조회
     */
    public Booking findByBookingNumber(String bookingNumber) {
        return bookingRepository.findByBookingNumber(bookingNumber)
                .orElseThrow(BookingNotFoundException::new);
    }

    /**
     * 예약 ID로 조회
     */
    public Booking findById(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(BookingNotFoundException::new);
    }

    /**
     * 회원의 예약 목록 조회
     */
    public List<Booking> getMyBookings(Long memberId) {
        return bookingRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    /**
     * 예약 취소
     * 1. Booking 취소
     * 2. Ticket 취소
     * 3. Event 재고 복구
     */
    @Transactional
    public void cancelBooking(Long bookingId) {
        Booking booking = findById(bookingId);

        // 예약 취소 (CONFIRMED는 예외 발생)
        booking.cancel();

        // 티켓 취소
        List<Ticket> tickets = ticketRepository.findByBookingId(bookingId);
        tickets.forEach(Ticket::cancel);

        // Event 재고 복구
        Event event = eventRepository.findById(booking.getEventId())
                .orElseThrow(EventNotFoundException::new);
        event.increaseAvailableTickets(booking.getTicketCount());

        log.info("예약 취소 완료 - bookingId: {}, 복구된 티켓 수: {}",
                bookingId, booking.getTicketCount());
    }

    /**
     * 만료된 예약 처리 (배치 스케줄러에서 호출)
     * 1. 만료된 예약 조회
     * 2. Booking 만료
     * 3. Ticket 취소
     * 4. Event 재고 복구
     */
    @Transactional
    public void expireBookings() {
        List<Booking> expiredBookings = bookingRepository.findExpiredBookings(
                BookingStatus.RESERVED,
                LocalDateTime.now()
        );

        for (Booking booking : expiredBookings) {
            try {
                // 예약 만료
                booking.expire();

                // 티켓 취소
                List<Ticket> tickets = ticketRepository.findByBookingId(booking.getId());
                tickets.forEach(Ticket::cancel);

                // Event 재고 복구
                Event event = eventRepository.findById(booking.getEventId())
                        .orElseThrow(EventNotFoundException::new);
                event.increaseAvailableTickets(booking.getTicketCount());

                log.info("만료 예약 처리 완료 - bookingId: {}, 복구된 티켓 수: {}",
                        booking.getId(), booking.getTicketCount());

            } catch (Exception e) {
                log.error("만료 예약 처리 실패 - bookingId: {}", booking.getId(), e);
                // 다음 배치에서 재시도
            }
        }

        log.info("만료 예약 배치 처리 완료 - 처리 건수: {}", expiredBookings.size());
    }

    /**
     * 중복 예약 확인
     */
    public boolean hasDuplicateBooking(Long memberId, Long eventId) {
        List<BookingStatus> activeStatuses = List.of(
                BookingStatus.RESERVED,
                BookingStatus.CONFIRMED
        );

        return bookingRepository.existsByMemberIdAndEventIdAndStatusIn(
                memberId, eventId, activeStatuses);
    }

}

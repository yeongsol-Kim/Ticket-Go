package com.yeongsol.ticketgo.domain.booking.service;

import com.yeongsol.ticketgo.domain.booking.exception.BookingNotFoundException;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.model.BookingStatus;
import com.yeongsol.ticketgo.domain.booking.repository.BookingRepository;
import com.yeongsol.ticketgo.domain.event.exception.EventNotFoundException;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
     * 대기열 배치 입장 - 여러 유저의 예약을 트랜잭션 1개로 생성 (벌크 A단계)
     *
     * 기존: 유저마다 createBooking()이 각자 @Transactional → 커밋 N번, event SELECT/UPDATE N번.
     * 개선: 배치 전체를 트랜잭션 1개로 묶어 event를 한 번만 로드·공유.
     *   → 커밋 1번, event UPDATE는 dirty checking으로 1번(마지막 재고값)으로 합쳐짐.
     *   → booking INSERT는 아직 건별(IDENTITY 때문, C단계에서 처리).
     *
     * 재고 소진 처리: 큐 순서대로 차감하다 재고가 부족해지면 그 지점에서 중단(break).
     *   decreaseAvailableTickets는 차감 전 in-memory 가드라 예외 시 DB 미변경 → 트랜잭션 안전.
     *   앞선 성공분은 유지, 나머지 유저는 큐에 남아 다음 사이클 처리(재고 0이면 자연히 탈락).
     *
     * @return 생성된 Booking 목록 (각 Booking에 memberId·id 포함 → 호출부가 세션 발급/큐 제거에 사용)
     */
    @Transactional
    public List<Booking> createBookingsBatch(Long eventId, List<QueueService.QueuedUser> users, long timeoutSeconds) {
        if (users.isEmpty()) return List.of();

        Event event = eventRepository.findById(eventId)
                .orElseThrow(EventNotFoundException::new);
        int price = event.getPrice().intValue();
        int available = event.getAvailableTickets();

        // 재고에 맞춰 처리할 유저를 큐 순서대로 선별 (경계 안전: 재고 60인데 200명이면 60명분만)
        List<QueueService.QueuedUser> toServe = new ArrayList<>();
        int need = 0;
        for (QueueService.QueuedUser user : users) {
            if (need + user.ticketCount() > available) break;
            need += user.ticketCount();
            toServe.add(user);
        }
        if (need == 0) {
            log.info("배치 재고 소진 - eventId={}, available={}", eventId, available);
            return List.of();
        }

        // ── B: 원자적 조건부 재고 차감 (오버부킹을 DB가 막음, @Version 불필요)
        int affected = eventRepository.decreaseStock(eventId, need);
        if (affected == 0) {
            // 로드 이후 재고가 변함(취소/만료 복원 등) → 배치 전체 재시도 유도
            throw new ObjectOptimisticLockingFailureException(Event.class, eventId);
        }

        List<Booking> created = new ArrayList<>(toServe.size());
        for (QueueService.QueuedUser user : toServe) {
            Booking booking = Booking.builder()
                    .memberId(user.memberId())
                    .eventId(eventId)
                    .bookingNumber(UUID.randomUUID().toString())
                    .ticketCount(user.ticketCount())
                    .totalAmount(price * user.ticketCount())
                    .timeoutSeconds(timeoutSeconds)
                    .build();

            created.add(bookingRepository.save(booking));
        }

        return created;
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

        // Event 재고 복구 - 원자적 UPDATE (차감 배치와 lost update 없이 행 락으로 직렬화)
        eventRepository.increaseStock(booking.getEventId(), booking.getTicketCount());

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

                // Event 재고 복구 - 원자적 UPDATE (차감 배치와 lost update 없이 행 락으로 직렬화)
                eventRepository.increaseStock(booking.getEventId(), booking.getTicketCount());

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

package com.yeongsol.ticketgo.domain.payment.service;

import com.yeongsol.ticketgo.domain.booking.exception.BookingExpiredException;
import com.yeongsol.ticketgo.domain.booking.exception.BookingNotFoundException;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.repository.BookingRepository;
import com.yeongsol.ticketgo.domain.payment.exception.PaymentAlreadyExistsException;
import com.yeongsol.ticketgo.domain.payment.exception.PaymentNotFoundException;
import com.yeongsol.ticketgo.domain.payment.model.Payment;
import com.yeongsol.ticketgo.domain.payment.model.PaymentMethod;
import com.yeongsol.ticketgo.domain.payment.model.PaymentStatus;
import com.yeongsol.ticketgo.domain.payment.repository.PaymentRepository;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;

    /**
     * 결제 요청
     */
    @Transactional
    public Payment requestPayment(Long bookingId, PaymentMethod method) {
        // 예약 조회
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(BookingNotFoundException::new);

        // 만료 체크
        if (booking.isExpired()) { throw new BookingExpiredException(); }

        // 중복 결제 방지
        if (paymentRepository.existsByBookingId(bookingId)) { throw new PaymentAlreadyExistsException(); }

        // Payment 생성
        Payment payment = Payment.builder()
                .bookingId(bookingId)
                .memberId(booking.getMemberId())
                .method(method)
                .amount(booking.getTotalAmount())
                .build();

        return paymentRepository.save(payment);
    }

    /**
     * 결제 승인 (PG 결제 완료 후)
     * 1. Payment 승인
     * 2. Booking 확정
     * 3. Ticket 생성 (실제 결제 완료 후 발행)
     */
    @Transactional
    public void approvePayment(Long paymentId, String paymentKey) {
        Payment payment = findById(paymentId);

        // 결제 승인
        payment.approve(paymentKey);

        // 예약 확정 (RESERVED → CONFIRMED)
        Booking booking = bookingRepository.findById(payment.getBookingId())
                .orElseThrow(BookingNotFoundException::new);
        booking.confirm();

        // 티켓 생성 (결제 완료 후 실제 티켓 발행)
        List<Ticket> tickets = createTickets(booking.getEventId(), booking.getId(), booking.getTicketCount());
        ticketRepository.saveAll(tickets);

        log.info("결제 승인 완료 - paymentId: {}, bookingId: {}, amount: {}",
                paymentId, booking.getId(), payment.getAmount());
    }

    /**
     * 결제 승인 - Pre-issued 방식 (UPDATE)
     * 사전 발급된 AVAILABLE 티켓을 조회해 bookingId 할당
     */
    @Transactional
    public void approvePaymentPreIssued(Long paymentId, String paymentKey) {
        Payment payment = findById(paymentId);
        payment.approve(paymentKey);

        Booking booking = bookingRepository.findById(payment.getBookingId())
                .orElseThrow(BookingNotFoundException::new);
        booking.confirm();

        for (int i = 0; i < booking.getTicketCount(); i++) {
            Ticket ticket = ticketRepository
                    .findFirstByEventIdAndStatus(booking.getEventId(), com.yeongsol.ticketgo.domain.ticket.model.TicketStatus.AVAILABLE)
                    .orElseThrow(() -> new IllegalStateException("사용 가능한 티켓이 없습니다"));
            ticket.assign(booking.getId());
        }

        log.info("결제 승인 완료 (Pre-issued) - paymentId: {}, bookingId: {}", paymentId, booking.getId());
    }

    /**
     * 결제 실패 처리
     */
    @Transactional
    public void failPayment(Long paymentId, String reason) {
        Payment payment = findById(paymentId);
        payment.fail(reason);

        log.warn("결제 실패 - paymentId: {}, reason: {}", paymentId, reason);
    }

    /**
     * 결제 취소
     */
    @Transactional
    public void cancelPayment(Long paymentId) {
        Payment payment = findById(paymentId);
        payment.cancel();

        log.info("결제 취소 - paymentId: {}", paymentId);
    }

    /**
     * 결제 환불
     */
    @Transactional
    public void refundPayment(Long paymentId) {
        Payment payment = findById(paymentId);
        payment.refund();

        log.info("결제 환불 - paymentId: {}, amount: {}", paymentId, payment.getAmount());
    }

    /**
     * 결제 ID로 조회
     */
    public Payment findById(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(PaymentNotFoundException::new);
    }

    /**
     * 예약별 결제 조회
     */
    public Payment findByBookingId(Long bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .orElseThrow(PaymentNotFoundException::new);
    }

    /**
     * 결제 키로 조회 (PG 웹훅용)
     */
    public Payment findByPaymentKey(String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(PaymentNotFoundException::new);
    }

    /**
     * 회원의 결제 내역 조회
     */
    public List<Payment> getMyPayments(Long memberId) {
        return paymentRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    /**
     * 특정 기간 승인된 결제 총액 (매출 통계)
     */
    public Long getTotalSalesAmount(LocalDateTime startDate, LocalDateTime endDate) {
        Long total = paymentRepository.getTotalAmountByStatusAndPeriod(
                PaymentStatus.APPROVED, startDate, endDate);
        return total != null ? total : 0L;
    }

    /**
     * 대기 중인 결제 모니터링
     * 5분 이상 대기 중인 결제 조회
     */
    public List<Payment> getPendingPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        return paymentRepository.findPendingPaymentsBefore(PaymentStatus.PENDING, threshold);
    }

    /**
     * Ticket 생성 헬퍼 메서드
     */
    private List<Ticket> createTickets(Long eventId, Long bookingId, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Ticket.builder()
                        .eventId(eventId)
                        .bookingId(bookingId)
                        .ticketNumber(UUID.randomUUID().toString())
                        .build())
                .collect(Collectors.toList());
    }
}

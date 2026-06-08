package com.yeongsol.ticketgo.integration;

import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.model.BookingStatus;
import com.yeongsol.ticketgo.domain.booking.repository.BookingRepository;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.repository.MemberRepository;
import com.yeongsol.ticketgo.domain.payment.model.Payment;
import com.yeongsol.ticketgo.domain.payment.model.PaymentMethod;
import com.yeongsol.ticketgo.domain.payment.model.PaymentStatus;
import com.yeongsol.ticketgo.domain.payment.repository.PaymentRepository;
import com.yeongsol.ticketgo.domain.payment.service.PaymentService;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.model.TicketStatus;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("결제 플로우 통합 테스트")
class PaymentFlowIntegrationTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Member testMember;
    private Event testEvent;

    @BeforeEach
    void setUp() {
        // 테스트 회원 생성
        testMember = Member.builder()
                .email("test@test.com")
                .password(passwordEncoder.encode("password123"))
                .name("테스트유저")
                .phoneNumber("010-1234-5678")
                .build();
        memberRepository.save(testMember);

        // 테스트 이벤트 생성
        testEvent = Event.builder()
                .name("테스트 콘서트")
                .description("통합 테스트용 이벤트")
                .startDateTime(LocalDateTime.now().plusDays(30))
                .saleStartDateTime(LocalDateTime.now().minusHours(1))
                .saleEndDateTime(LocalDateTime.now().plusDays(29))
                .totalTickets(100)
                .price(50000)
                .venue("테스트 공연장")
                .build();
        testEvent.startSale();
        eventRepository.save(testEvent);
    }

    @Test
    void setUpTest() {

    }
    @Test
    @DisplayName("1단계: 예약 생성 - 티켓도 함께 생성되는지 확인")
    void step1_createBooking() {
        // Given
        Long memberId = testMember.getId();
        Long eventId = testEvent.getId();
        int ticketCount = 2;

        // When: 예약 생성
        Booking booking = bookingService.createBooking(memberId, eventId, ticketCount, 600L);

        // Then 1: 예약 기본 정보 확인
        assertThat(booking).isNotNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.RESERVED);
        assertThat(booking.getTicketCount()).isEqualTo(ticketCount);
        assertThat(booking.getTotalAmount()).isEqualTo(100000); // 50,000 * 2

        // Then 2: 예약 시점에는 티켓 미생성 (결제 승인 후 생성됨)
        List<Ticket> tickets = ticketRepository.findByBookingId(booking.getId());
        assertThat(tickets).isEmpty();

        // Then 3: 이벤트 재고 감소 확인
        Event updatedEvent = eventRepository.findById(eventId).orElseThrow();
        assertThat(updatedEvent.getAvailableTickets()).isEqualTo(98); // 100 - 2
    }

    @Test
    @DisplayName("2단계: 결제 요청 → 승인 → 예약 확정")
    void step2_paymentFlow() {
        // Given: 예약 생성
        Booking booking = bookingService.createBooking(testMember.getId(), testEvent.getId(), 2, 600L);

        // When 1: 결제 요청
        Payment payment = paymentService.requestPayment(booking.getId(), PaymentMethod.CARD);

        // Then 1: 결제 요청 상태 확인
        assertThat(payment).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getBookingId()).isEqualTo(booking.getId());
        assertThat(payment.getAmount()).isEqualTo(100000L);
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CARD);

        // When 2: 결제 승인
        String paymentKey = "test-payment-key-12345";
        paymentService.approvePayment(payment.getId(), paymentKey);

        // Then 2-1: 결제 승인 상태 확인
        Payment approvedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(approvedPayment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approvedPayment.getPaymentKey()).isEqualTo(paymentKey);
        assertThat(approvedPayment.getCompletedAt()).isNotNull();

        // Then 2-2: 예약 확정 상태 확인
        Booking confirmedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(confirmedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmedBooking.getConfirmedAt()).isNotNull();

        // Then 2-3: 티켓은 여전히 BOOKED 상태
        List<Ticket> tickets = ticketRepository.findByBookingId(booking.getId());
        assertThat(tickets).hasSize(2);
        assertThat(tickets).allMatch(t -> t.getStatus() == TicketStatus.BOOKED);
    }
}

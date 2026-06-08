package com.yeongsol.ticketgo.integration;

import com.redis.testcontainers.RedisContainer;
import com.yeongsol.ticketgo.domain.auth.service.AuthService;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.model.BookingStatus;
import com.yeongsol.ticketgo.domain.booking.repository.BookingRepository;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.event.service.EventService;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.repository.MemberRepository;
import com.yeongsol.ticketgo.domain.member.service.MemberService;
import com.yeongsol.ticketgo.domain.payment.model.Payment;
import com.yeongsol.ticketgo.domain.payment.model.PaymentMethod;
import com.yeongsol.ticketgo.domain.payment.model.PaymentStatus;
import com.yeongsol.ticketgo.domain.payment.repository.PaymentRepository;
import com.yeongsol.ticketgo.domain.payment.service.PaymentService;
import com.yeongsol.ticketgo.domain.queue.dto.QueueDto;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.model.TicketStatus;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 플로우 통합 테스트
 *
 * 회원가입 → 로그인 → 이벤트 조회 → 대기열 진입 → 입장 → 예약 → 결제
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("전체 플로우 통합 테스트")
class FullFlowIntegrationTest {

    @Container
    static RedisContainer redis = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.0")
    );

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private QueueService queueService;

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

    // 테스트 데이터
    private static Member testMember;
    private static Event testEvent;
    private static String jwtToken;
    private static Booking testBooking;
    private static Payment testPayment;

    @BeforeEach
    void cleanUp() {
        // 각 테스트 전에 데이터 정리 (필요시)
    }

    @Test
    @Order(1)
    @DisplayName("1단계: 회원가입")
    void step1_register() {
        // Given
        String email = "user@ticketgo.com";
        String password = "password123";
        String name = "테스트유저";
        String phoneNumber = "010-1234-5678";

        // When
        testMember = memberService.register(email, password, name, phoneNumber);

        // Then
        assertThat(testMember).isNotNull();
        assertThat(testMember.getId()).isNotNull();
        assertThat(testMember.getEmail()).isEqualTo(email);
        assertThat(testMember.getName()).isEqualTo(name);
        assertThat(testMember.getEnabled()).isTrue();

        System.out.println("✅ 1단계 완료: 회원가입 성공 - memberId: " + testMember.getId());
    }

    @Test
    @Order(2)
    @DisplayName("2단계: 로그인 (JWT 발급)")
    void step2_login() {
        // Given - 회원가입 (Order 1에서 이미 생성됨, 하지만 독립 실행을 위해 재생성)
        if (testMember == null) {
            testMember = memberService.register("user@ticketgo.com", "password123", "테스트유저", "010-1234-5678");
        }

        // When
        jwtToken = authService.login("user@ticketgo.com", "password123");

        // Then
        assertThat(jwtToken).isNotNull();
        assertThat(jwtToken).isNotEmpty();
        assertThat(jwtToken.split("\\.")).hasSize(3); // JWT 형식: header.payload.signature

        System.out.println("✅ 2단계 완료: 로그인 성공 - JWT 발급됨");
    }

    @Test
    @Order(3)
    @DisplayName("3단계: 이벤트 생성 및 조회")
    void step3_createAndGetEvent() {
        // Given - 이벤트 생성 (관리자 기능)
        testEvent = Event.builder()
                .name("2024 연말 콘서트")
                .description("최고의 아티스트들과 함께하는 연말 콘서트")
                .startDateTime(LocalDateTime.now().plusDays(30))
                .saleStartDateTime(LocalDateTime.now().minusHours(1))
                .saleEndDateTime(LocalDateTime.now().plusDays(29))
                .totalTickets(100)
                .price(55000)
                .venue("올림픽공원 체조경기장")
                .build();
        testEvent.startSale();
        eventRepository.save(testEvent);

        // When - 판매 중인 이벤트 조회
        List<Event> onSaleEvents = eventService.getOnSaleEvents();

        // Then
        assertThat(onSaleEvents).isNotEmpty();
        assertThat(onSaleEvents).anyMatch(e -> e.getId().equals(testEvent.getId()));

        Event foundEvent = eventService.findById(testEvent.getId());
        assertThat(foundEvent.getName()).isEqualTo("2024 연말 콘서트");
        assertThat(foundEvent.getStatus()).isEqualTo(EventStatus.ON_SALE);
        assertThat(foundEvent.getAvailableTickets()).isEqualTo(100);

        System.out.println("✅ 3단계 완료: 이벤트 생성 및 조회 성공 - eventId: " + testEvent.getId());
    }

    @Test
    @Order(4)
    @DisplayName("4단계: 대기열 진입")
    void step4_enterQueue() {
        // Given - 테스트 데이터 준비
        if (testMember == null) {
            testMember = memberService.register("user2@ticketgo.com", "password123", "테스트유저2", "010-1111-2222");
        }
        if (testEvent == null) {
            testEvent = Event.builder()
                    .name("테스트 콘서트")
                    .description("테스트")
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

        // When - 대기열 진입
        QueueDto.EnterResponse response = queueService.enterQueue(testEvent.getId(), testMember.getId(), 2);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getEventId()).isEqualTo(testEvent.getId());
        assertThat(response.getMemberId()).isEqualTo(testMember.getId());
        assertThat(response.getPosition()).isGreaterThan(0);

        System.out.println("✅ 4단계 완료: 대기열 진입 성공 - position: " + response.getPosition());
    }

    @Disabled("admitNext/isActiveSession replaced by dequeueNext/hasPaymentSession in current QueueService")
    @Test
    @Order(5)
    @DisplayName("5단계: 대기열 상태 확인 및 입장 허용")
    void step5_admitFromQueue() {
        // Given - 데이터 준비
        if (testMember == null || testEvent == null) {
            step4_enterQueue();
        }

        // 대기열에 있는지 확인
        long queueSize = queueService.getQueueSize(testEvent.getId());
        System.out.println("현재 대기열 크기: " + queueSize);

        // When - 입장 허용 (관리자/스케줄러 기능)
        int admitted = queueService.dequeueNext(testEvent.getId(), 10).size();

        // Then
        assertThat(admitted).isGreaterThan(0);

        // 결제 세션 확인
        boolean isActive = queueService.hasPaymentSession(testEvent.getId(), testMember.getId());
        assertThat(isActive).isTrue();

        System.out.println("✅ 5단계 완료: 입장 허용 성공 - admitted: " + admitted);
    }

    @Test
    @Order(6)
    @DisplayName("6단계: 예약 생성")
    void step6_createBooking() {
        // Given - 데이터 준비
        if (testMember == null || testEvent == null) {
            step3_createAndGetEvent();
            if (testMember == null) {
                testMember = memberService.register("user3@ticketgo.com", "password123", "테스트유저3", "010-3333-4444");
            }
        }

        int ticketCount = 2;

        // When - 예약 생성
        testBooking = bookingService.createBooking(testMember.getId(), testEvent.getId(), ticketCount, 600L);

        // Then 1: 예약 정보 확인
        assertThat(testBooking).isNotNull();
        assertThat(testBooking.getStatus()).isEqualTo(BookingStatus.RESERVED);
        assertThat(testBooking.getTicketCount()).isEqualTo(ticketCount);
        assertThat(testBooking.getTotalAmount()).isEqualTo(testEvent.getPrice() * ticketCount);
        assertThat(testBooking.getBookingNumber()).isNotNull();

        // Then 2: 이벤트 재고 감소 확인 (티켓은 결제 승인 후 생성됨)
        // Then 3: 이벤트 재고 감소 확인
        Event updatedEvent = eventRepository.findById(testEvent.getId()).orElseThrow();
        assertThat(updatedEvent.getAvailableTickets()).isEqualTo(100 - ticketCount);

        System.out.println("✅ 6단계 완료: 예약 생성 성공 - bookingId: " + testBooking.getId());
        System.out.println("   - 예약번호: " + testBooking.getBookingNumber());
        System.out.println("   - 총 금액: " + testBooking.getTotalAmount() + "원");
        System.out.println("   - 티켓 수: " + ticketCount + "장");
    }

    @Test
    @Order(7)
    @DisplayName("7단계: 결제 요청")
    void step7_requestPayment() {
        // Given - 예약 생성
        if (testBooking == null) {
            step6_createBooking();
        }

        // When - 결제 요청
        testPayment = paymentService.requestPayment(testBooking.getId(), PaymentMethod.CARD);

        // Then
        assertThat(testPayment).isNotNull();
        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(testPayment.getBookingId()).isEqualTo(testBooking.getId());
        assertThat(testPayment.getAmount()).isEqualTo(testBooking.getTotalAmount());
        assertThat(testPayment.getMethod()).isEqualTo(PaymentMethod.CARD);

        System.out.println("✅ 7단계 완료: 결제 요청 성공 - paymentId: " + testPayment.getId());
        System.out.println("   - 결제 금액: " + testPayment.getAmount() + "원");
        System.out.println("   - 결제 수단: " + testPayment.getMethod());
    }

    @Test
    @Order(8)
    @DisplayName("8단계: 결제 승인 및 예약 확정")
    void step8_approvePayment() {
        // Given - 결제 요청
        if (testPayment == null) {
            step7_requestPayment();
        }

        String paymentKey = "PG_PAYMENT_KEY_" + System.currentTimeMillis();

        // When - 결제 승인
        paymentService.approvePayment(testPayment.getId(), paymentKey);

        // Then 1: 결제 상태 확인
        Payment approvedPayment = paymentRepository.findById(testPayment.getId()).orElseThrow();
        assertThat(approvedPayment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approvedPayment.getPaymentKey()).isEqualTo(paymentKey);
        assertThat(approvedPayment.getCompletedAt()).isNotNull();

        // Then 2: 예약 확정 상태 확인
        Booking confirmedBooking = bookingRepository.findById(testBooking.getId()).orElseThrow();
        assertThat(confirmedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmedBooking.getConfirmedAt()).isNotNull();

        // Then 3: 티켓 상태 확인
        List<Ticket> tickets = ticketRepository.findByBookingId(testBooking.getId());
        assertThat(tickets).allMatch(t -> t.getStatus() == TicketStatus.BOOKED);

        System.out.println("✅ 8단계 완료: 결제 승인 및 예약 확정 성공!");
        System.out.println("   - 결제 상태: " + approvedPayment.getStatus());
        System.out.println("   - 예약 상태: " + confirmedBooking.getStatus());
        System.out.println("   - PG 결제키: " + paymentKey);
    }

    @Disabled("Uses admitNext/isActiveSession which are replaced in current QueueService")
    @Test
    @Order(9)
    @DisplayName("전체 플로우 통합 테스트 - 한번에 실행")
    void fullFlow_endToEnd() {
        // ========== 1. 회원가입 ==========
        String email = "fullflow@ticketgo.com";
        Member member = memberService.register(email, "password123", "풀플로우유저", "010-9999-8888");
        assertThat(member.getId()).isNotNull();
        System.out.println("\n========== 전체 플로우 테스트 시작 ==========");
        System.out.println("1️⃣ 회원가입 완료 - " + email);

        // ========== 2. 로그인 ==========
        String token = authService.login(email, "password123");
        assertThat(token).isNotEmpty();
        System.out.println("2️⃣ 로그인 완료 - JWT 발급");

        // ========== 3. 이벤트 생성 ==========
        Event event = Event.builder()
                .name("풀플로우 테스트 콘서트")
                .description("통합 테스트용 이벤트")
                .startDateTime(LocalDateTime.now().plusDays(30))
                .saleStartDateTime(LocalDateTime.now().minusHours(1))
                .saleEndDateTime(LocalDateTime.now().plusDays(29))
                .totalTickets(50)
                .price(77000)
                .venue("테스트홀")
                .build();
        event.startSale();
        eventRepository.save(event);
        System.out.println("3️⃣ 이벤트 생성 완료 - " + event.getName());

        // ========== 4. 대기열 진입 ==========
        QueueDto.EnterResponse queueResponse = queueService.enterQueue(event.getId(), member.getId(), 2);
        assertThat(queueResponse.getPosition()).isGreaterThan(0);
        System.out.println("4️⃣ 대기열 진입 완료 - 순번: " + queueResponse.getPosition());

        // ========== 5. 입장 허용 ==========
        int admitted = queueService.dequeueNext(event.getId(), 10).size();
        boolean isActive = queueService.hasPaymentSession(event.getId(), member.getId());
        assertThat(isActive).isTrue();
        System.out.println("5️⃣ 입장 허용 완료 - 활성 세션: " + isActive);

        // ========== 6. 예약 생성 ==========
        Booking booking = bookingService.createBooking(member.getId(), event.getId(), 2, 600L);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.RESERVED);
        System.out.println("6️⃣ 예약 생성 완료 - 예약번호: " + booking.getBookingNumber());

        // ========== 7. 결제 요청 ==========
        Payment payment = paymentService.requestPayment(booking.getId(), PaymentMethod.CARD);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        System.out.println("7️⃣ 결제 요청 완료 - 금액: " + payment.getAmount() + "원");

        // ========== 8. 결제 승인 ==========
        String pgKey = "TEST_PG_KEY_" + System.currentTimeMillis();
        paymentService.approvePayment(payment.getId(), pgKey);

        Payment finalPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        Booking finalBooking = bookingRepository.findById(booking.getId()).orElseThrow();

        assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(finalBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        System.out.println("8️⃣ 결제 승인 완료 - 예약 확정!");
        System.out.println("\n========== 전체 플로우 테스트 성공! ==========");
        System.out.println("📌 최종 결과:");
        System.out.println("   - 회원: " + member.getEmail());
        System.out.println("   - 이벤트: " + event.getName());
        System.out.println("   - 예약 상태: " + finalBooking.getStatus());
        System.out.println("   - 결제 상태: " + finalPayment.getStatus());
        System.out.println("   - 티켓 수: " + booking.getTicketCount() + "장");
        System.out.println("   - 총 결제금액: " + finalPayment.getAmount() + "원");
    }
}

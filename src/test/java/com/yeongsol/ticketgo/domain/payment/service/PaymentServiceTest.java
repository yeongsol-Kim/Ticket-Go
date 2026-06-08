package com.yeongsol.ticketgo.domain.payment.service;

import com.yeongsol.ticketgo.domain.booking.exception.BookingExpiredException;
import com.yeongsol.ticketgo.domain.booking.exception.BookingNotFoundException;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.model.BookingStatus;
import com.yeongsol.ticketgo.domain.booking.repository.BookingRepository;
import com.yeongsol.ticketgo.domain.payment.exception.PaymentAlreadyExistsException;
import com.yeongsol.ticketgo.domain.payment.exception.PaymentNotFoundException;
import com.yeongsol.ticketgo.domain.payment.model.Payment;
import com.yeongsol.ticketgo.domain.payment.model.PaymentMethod;
import com.yeongsol.ticketgo.domain.payment.model.PaymentStatus;
import com.yeongsol.ticketgo.domain.payment.repository.PaymentRepository;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * PaymentService 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService 단위 테스트")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private PaymentService paymentService;

    @Nested
    @DisplayName("requestPayment 테스트")
    class RequestPaymentTest {

        @Test
        @DisplayName("결제 요청 성공")
        void requestPayment_success() {
            // Given
            Long bookingId = 1L;
            Long memberId = 1L;
            PaymentMethod method = PaymentMethod.CARD;

            Booking mockBooking = createMockBooking(bookingId, memberId, 50000);
            given(bookingRepository.findById(bookingId)).willReturn(Optional.of(mockBooking));
            given(paymentRepository.existsByBookingId(bookingId)).willReturn(false);
            given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> {
                Payment payment = invocation.getArgument(0);
                setFieldValue(payment, "id", 1L);
                return payment;
            });

            // When
            Payment result = paymentService.requestPayment(bookingId, method);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getBookingId()).isEqualTo(bookingId);
            assertThat(result.getMemberId()).isEqualTo(memberId);
            assertThat(result.getMethod()).isEqualTo(method);
            assertThat(result.getAmount()).isEqualTo(50000);
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);

            then(bookingRepository).should(times(1)).findById(bookingId);
            then(paymentRepository).should(times(1)).existsByBookingId(bookingId);
            then(paymentRepository).should(times(1)).save(any(Payment.class));
        }

        @Test
        @DisplayName("결제 요청 실패 - 존재하지 않는 예약")
        void requestPayment_bookingNotFound_throwsException() {
            // Given
            Long bookingId = 999L;
            given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> paymentService.requestPayment(bookingId, PaymentMethod.CARD))
                    .isInstanceOf(BookingNotFoundException.class);

            then(paymentRepository).should(never()).save(any(Payment.class));
        }

        @Test
        @DisplayName("결제 요청 실패 - 만료된 예약")
        void requestPayment_expiredBooking_throwsException() {
            // Given
            Long bookingId = 1L;
            Booking expiredBooking = createMockBooking(bookingId, 1L, 50000);
            // 만료 시간을 과거로 설정
            setFieldValue(expiredBooking, "expiresAt", LocalDateTime.now().minusMinutes(1));

            given(bookingRepository.findById(bookingId)).willReturn(Optional.of(expiredBooking));

            // When & Then
            assertThatThrownBy(() -> paymentService.requestPayment(bookingId, PaymentMethod.CARD))
                    .isInstanceOf(BookingExpiredException.class);

            then(paymentRepository).should(never()).existsByBookingId(any());
            then(paymentRepository).should(never()).save(any(Payment.class));
        }

        @Test
        @DisplayName("결제 요청 실패 - 중복 결제")
        void requestPayment_duplicatePayment_throwsException() {
            // Given
            Long bookingId = 1L;
            Booking mockBooking = createMockBooking(bookingId, 1L, 50000);

            given(bookingRepository.findById(bookingId)).willReturn(Optional.of(mockBooking));
            given(paymentRepository.existsByBookingId(bookingId)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> paymentService.requestPayment(bookingId, PaymentMethod.CARD))
                    .isInstanceOf(PaymentAlreadyExistsException.class);

            then(paymentRepository).should(never()).save(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("approvePayment 테스트")
    class ApprovePaymentTest {

        @Test
        @DisplayName("결제 승인 성공")
        void approvePayment_success() {
            // Given
            Long paymentId = 1L;
            Long bookingId = 1L;
            String paymentKey = "pg_payment_key_123";

            Payment mockPayment = createMockPayment(paymentId, bookingId, 1L);
            Booking mockBooking = createMockBooking(bookingId, 1L, 50000);

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(mockPayment));
            given(bookingRepository.findById(bookingId)).willReturn(Optional.of(mockBooking));

            // When
            paymentService.approvePayment(paymentId, paymentKey);

            // Then
            assertThat(mockPayment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(mockPayment.getPaymentKey()).isEqualTo(paymentKey);
            assertThat(mockBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

            then(ticketRepository).should(times(1)).saveAll(anyList());
        }

        @Test
        @DisplayName("결제 승인 실패 - 존재하지 않는 결제")
        void approvePayment_paymentNotFound_throwsException() {
            // Given
            Long paymentId = 999L;
            given(paymentRepository.findById(paymentId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> paymentService.approvePayment(paymentId, "key"))
                    .isInstanceOf(PaymentNotFoundException.class);
        }

        @Test
        @DisplayName("결제 승인 실패 - 이미 승인된 결제")
        void approvePayment_alreadyApproved_throwsException() {
            // Given
            Long paymentId = 1L;
            Payment approvedPayment = createMockPayment(paymentId, 1L, 1L);
            approvedPayment.approve("existing_key"); // 이미 승인됨

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(approvedPayment));

            // When & Then
            assertThatThrownBy(() -> paymentService.approvePayment(paymentId, "new_key"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Payment is not in pending state");
        }
    }

    @Nested
    @DisplayName("failPayment 테스트")
    class FailPaymentTest {

        @Test
        @DisplayName("결제 실패 처리 성공")
        void failPayment_success() {
            // Given
            Long paymentId = 1L;
            String reason = "카드 한도 초과";
            Payment mockPayment = createMockPayment(paymentId, 1L, 1L);

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(mockPayment));

            // When
            paymentService.failPayment(paymentId, reason);

            // Then
            assertThat(mockPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(mockPayment.getFailureReason()).isEqualTo(reason);
        }
    }

    @Nested
    @DisplayName("cancelPayment 테스트")
    class CancelPaymentTest {

        @Test
        @DisplayName("결제 취소 성공")
        void cancelPayment_success() {
            // Given
            Long paymentId = 1L;
            Payment approvedPayment = createMockPayment(paymentId, 1L, 1L);
            approvedPayment.approve("payment_key"); // 승인 상태로 변경

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(approvedPayment));

            // When
            paymentService.cancelPayment(paymentId);

            // Then
            assertThat(approvedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        }

        @Test
        @DisplayName("결제 취소 실패 - 대기 중인 결제")
        void cancelPayment_pendingPayment_throwsException() {
            // Given
            Long paymentId = 1L;
            Payment pendingPayment = createMockPayment(paymentId, 1L, 1L);

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(pendingPayment));

            // When & Then
            assertThatThrownBy(() -> paymentService.cancelPayment(paymentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Only approved payments can be cancelled");
        }
    }

    @Nested
    @DisplayName("refundPayment 테스트")
    class RefundPaymentTest {

        @Test
        @DisplayName("결제 환불 성공")
        void refundPayment_success() {
            // Given
            Long paymentId = 1L;
            Payment approvedPayment = createMockPayment(paymentId, 1L, 1L);
            approvedPayment.approve("payment_key");

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(approvedPayment));

            // When
            paymentService.refundPayment(paymentId);

            // Then
            assertThat(approvedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        @DisplayName("결제 환불 실패 - 승인되지 않은 결제")
        void refundPayment_notApproved_throwsException() {
            // Given
            Long paymentId = 1L;
            Payment pendingPayment = createMockPayment(paymentId, 1L, 1L);

            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(pendingPayment));

            // When & Then
            assertThatThrownBy(() -> paymentService.refundPayment(paymentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Only approved payments can be refunded");
        }
    }

    @Nested
    @DisplayName("findById 테스트")
    class FindByIdTest {

        @Test
        @DisplayName("결제 ID로 조회 성공")
        void findById_success() {
            // Given
            Long paymentId = 1L;
            Payment mockPayment = createMockPayment(paymentId, 1L, 1L);
            given(paymentRepository.findById(paymentId)).willReturn(Optional.of(mockPayment));

            // When
            Payment result = paymentService.findById(paymentId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(paymentId);
        }

        @Test
        @DisplayName("결제 ID로 조회 실패")
        void findById_notFound_throwsException() {
            // Given
            Long paymentId = 999L;
            given(paymentRepository.findById(paymentId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> paymentService.findById(paymentId))
                    .isInstanceOf(PaymentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("findByBookingId 테스트")
    class FindByBookingIdTest {

        @Test
        @DisplayName("예약별 결제 조회 성공")
        void findByBookingId_success() {
            // Given
            Long bookingId = 1L;
            Payment mockPayment = createMockPayment(1L, bookingId, 1L);
            given(paymentRepository.findByBookingId(bookingId)).willReturn(Optional.of(mockPayment));

            // When
            Payment result = paymentService.findByBookingId(bookingId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getBookingId()).isEqualTo(bookingId);
        }
    }

    @Nested
    @DisplayName("getMyPayments 테스트")
    class GetMyPaymentsTest {

        @Test
        @DisplayName("회원의 결제 내역 조회 성공")
        void getMyPayments_success() {
            // Given
            Long memberId = 1L;
            List<Payment> mockPayments = List.of(
                    createMockPayment(1L, 1L, memberId),
                    createMockPayment(2L, 2L, memberId)
            );
            given(paymentRepository.findByMemberIdOrderByCreatedAtDesc(memberId))
                    .willReturn(mockPayments);

            // When
            List<Payment> result = paymentService.getMyPayments(memberId);

            // Then
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("getTotalSalesAmount 테스트")
    class GetTotalSalesAmountTest {

        @Test
        @DisplayName("매출 통계 조회 성공")
        void getTotalSalesAmount_success() {
            // Given
            LocalDateTime startDate = LocalDateTime.now().minusDays(7);
            LocalDateTime endDate = LocalDateTime.now();

            given(paymentRepository.getTotalAmountByStatusAndPeriod(
                    eq(PaymentStatus.APPROVED), any(), any()
            )).willReturn(1000000L);

            // When
            Long result = paymentService.getTotalSalesAmount(startDate, endDate);

            // Then
            assertThat(result).isEqualTo(1000000L);
        }

        @Test
        @DisplayName("매출 통계 조회 - 결과 없음")
        void getTotalSalesAmount_null_returnsZero() {
            // Given
            LocalDateTime startDate = LocalDateTime.now().minusDays(7);
            LocalDateTime endDate = LocalDateTime.now();

            given(paymentRepository.getTotalAmountByStatusAndPeriod(
                    eq(PaymentStatus.APPROVED), any(), any()
            )).willReturn(null);

            // When
            Long result = paymentService.getTotalSalesAmount(startDate, endDate);

            // Then
            assertThat(result).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("getPendingPayments 테스트")
    class GetPendingPaymentsTest {

        @Test
        @DisplayName("대기 중인 결제 조회 성공")
        void getPendingPayments_success() {
            // Given
            List<Payment> pendingPayments = List.of(
                    createMockPayment(1L, 1L, 1L),
                    createMockPayment(2L, 2L, 2L)
            );

            given(paymentRepository.findPendingPaymentsBefore(
                    eq(PaymentStatus.PENDING), any(LocalDateTime.class)
            )).willReturn(pendingPayments);

            // When
            List<Payment> result = paymentService.getPendingPayments();

            // Then
            assertThat(result).hasSize(2);
        }
    }

    // ===== Helper Methods =====

    private Booking createMockBooking(Long id, Long memberId, int totalAmount) {
        Booking booking = Booking.builder()
                .memberId(memberId)
                .eventId(1L)
                .bookingNumber("booking-" + id)
                .ticketCount(1)
                .totalAmount(totalAmount)
                .timeoutSeconds(600)
                .build();

        setFieldValue(booking, "id", id);
        return booking;
    }

    private Payment createMockPayment(Long id, Long bookingId, Long memberId) {
        Payment payment = Payment.builder()
                .bookingId(bookingId)
                .memberId(memberId)
                .method(PaymentMethod.CARD)
                .amount(50000)
                .build();

        setFieldValue(payment, "id", id);
        return payment;
    }

    private void setFieldValue(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

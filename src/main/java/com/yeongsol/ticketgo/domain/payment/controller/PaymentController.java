package com.yeongsol.ticketgo.domain.payment.controller;

import com.yeongsol.ticketgo.config.security.SecurityUtil;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.payment.model.Payment;
import com.yeongsol.ticketgo.domain.payment.model.PaymentMethod;
import com.yeongsol.ticketgo.domain.payment.service.PaymentService;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 관리 API
 */
@Tag(name = "Payment", description = "결제 API")
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final QueueService queueService;

    @Operation(summary = "결제 요청", description = "예약에 대한 결제를 요청합니다",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @PostMapping
    public ResponseEntity<?> requestPayment(@Valid @RequestBody PaymentRequest request) {
        Long memberId = SecurityUtil.getCurrentMemberId();

        // 결제 세션 검증 - 대기열을 정상 통과한 사용자만 결제 가능
        Booking booking = bookingService.findById(request.bookingId());
        if (!queueService.hasPaymentSession(booking.getEventId(), memberId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("결제 세션이 없습니다. 대기열을 통해 입장해주세요"));
        }

        Payment payment = paymentService.requestPayment(request.bookingId(), request.method());

        // 결제 세션 제거
        queueService.removePaymentSession(booking.getEventId(), memberId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PaymentResponse.from(payment));
    }

    /**
     * 결제 승인 (PG사 콜백)
     * POST /api/payments/{id}/approve
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approvePayment(
            @PathVariable Long id,
            @Valid @RequestBody ApproveRequest request) {

        paymentService.approvePayment(id, request.paymentKey());
        return ResponseEntity.ok(new SuccessResponse("결제가 승인되었습니다"));
    }

    /**
     * 결제 승인 - Pre-issued 방식 (UPDATE)
     * POST /api/payments/{id}/approve-v2
     */
    @PostMapping("/{id}/approve-v2")
    public ResponseEntity<?> approvePaymentPreIssued(
            @PathVariable Long id,
            @Valid @RequestBody ApproveRequest request) {

        paymentService.approvePaymentPreIssued(id, request.paymentKey());
        return ResponseEntity.ok(new SuccessResponse("결제가 승인되었습니다 (Pre-issued)"));
    }

    /**
     * 결제 실패 처리 (PG사 콜백)
     * POST /api/payments/{id}/fail
     */
    @PostMapping("/{id}/fail")
    public ResponseEntity<?> failPayment(
            @PathVariable Long id,
            @Valid @RequestBody FailRequest request) {

        paymentService.failPayment(id, request.reason());
        return ResponseEntity.ok(new SuccessResponse("결제 실패 처리되었습니다"));
    }

    /**
     * 결제 취소
     * POST /api/payments/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelPayment(@PathVariable Long id) {
        // TODO: 본인 결제인지 확인

        paymentService.cancelPayment(id);
        return ResponseEntity.ok(new SuccessResponse("결제가 취소되었습니다"));
    }

    /**
     * 결제 환불
     * POST /api/payments/{id}/refund
     */
    @PostMapping("/{id}/refund")
    public ResponseEntity<?> refundPayment(@PathVariable Long id) {
        // TODO: 본인 결제인지 확인 또는 관리자 권한

        paymentService.refundPayment(id);
        return ResponseEntity.ok(new SuccessResponse("환불이 처리되었습니다"));
    }

    /**
     * 예약별 결제 조회
     * GET /api/payments/booking/{bookingId}
     */
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<?> getPaymentByBooking(@PathVariable Long bookingId) {
        Payment payment = paymentService.findByBookingId(bookingId);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    @Operation(summary = "내 결제 내역 조회", description = "로그인한 사용자의 결제 내역을 조회합니다",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @GetMapping("/me")
    public ResponseEntity<?> getMyPayments() {
        Long memberId = SecurityUtil.getCurrentMemberId();

        List<Payment> payments = paymentService.getMyPayments(memberId);
        List<PaymentResponse> response = payments.stream()
                .map(PaymentResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 상세 조회
     * GET /api/payments/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPayment(@PathVariable Long id) {
        Payment payment = paymentService.findById(id);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    /**
     * PG사 웹훅 (외부 호출)
     * POST /api/payments/webhook
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook(@Valid @RequestBody WebhookRequest request) {
        try {
            Payment payment = paymentService.findByPaymentKey(request.paymentKey());

            if ("SUCCESS".equals(request.status())) {
                paymentService.approvePayment(payment.getId(), request.paymentKey());
            } else {
                paymentService.failPayment(payment.getId(), request.message());
            }

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("웹훅 처리 실패 - paymentKey: {}", request.paymentKey(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ===== Request/Response DTOs =====

    record PaymentRequest(
            @NotNull(message = "예약 ID는 필수입니다")
            Long bookingId,

            @NotNull(message = "결제 수단은 필수입니다")
            PaymentMethod method
    ) {}

    record ApproveRequest(
            @NotBlank(message = "결제 키는 필수입니다")
            String paymentKey
    ) {}

    record FailRequest(
            @NotBlank(message = "실패 사유는 필수입니다")
            String reason
    ) {}

    record WebhookRequest(
            @NotBlank(message = "결제 키는 필수입니다")
            String paymentKey,

            @NotBlank(message = "결제 상태는 필수입니다")
            String status,

            String message
    ) {}

    record PaymentResponse(
            Long id,
            Long bookingId,
            String paymentKey,
            String method,
            String status,
            Integer amount,
            LocalDateTime requestedAt,
            LocalDateTime completedAt,
            String failureReason
    ) {
        static PaymentResponse from(Payment payment) {
            return new PaymentResponse(
                    payment.getId(),
                    payment.getBookingId(),
                    payment.getPaymentKey(),
                    payment.getMethod().name(),
                    payment.getStatus().name(),
                    payment.getAmount(),
                    payment.getRequestedAt(),
                    payment.getCompletedAt(),
                    payment.getFailureReason()
            );
        }
    }

    record SuccessResponse(String message) {}
    record ErrorResponse(String message) {}
}

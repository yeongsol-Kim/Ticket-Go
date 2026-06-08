package com.yeongsol.ticketgo.domain.payment.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(length = 100)
    private String paymentKey;  // 외부 PG 참조 키

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private Integer amount;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Column
    private LocalDateTime completedAt;

    @Column(length = 500)
    private String failureReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Payment(Long bookingId, Long memberId, PaymentMethod method, Integer amount) {
        this.bookingId = bookingId;
        this.memberId = memberId;
        this.method = method;
        this.status = PaymentStatus.PENDING;
        this.amount = amount;
        this.requestedAt = LocalDateTime.now();
    }

    // 비즈니스 로직
    public void approve(String paymentKey) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment is not in pending state");
        }
        this.status = PaymentStatus.APPROVED;
        this.paymentKey = paymentKey;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment is not in pending state");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (this.status != PaymentStatus.APPROVED) {
            throw new IllegalStateException("Only approved payments can be cancelled");
        }
        this.status = PaymentStatus.CANCELLED;
    }

    public void refund() {
        if (this.status != PaymentStatus.APPROVED) {
            throw new IllegalStateException("Only approved payments can be refunded");
        }
        this.status = PaymentStatus.REFUNDED;
    }
}

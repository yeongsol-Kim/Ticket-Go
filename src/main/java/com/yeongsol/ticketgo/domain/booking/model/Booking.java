package com.yeongsol.ticketgo.domain.booking.model;

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
@Table(name = "bookings")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false, unique = true, length = 36)
    private String bookingNumber;  // UUID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(nullable = false)
    private Integer ticketCount;

    @Column(nullable = false, precision = 10, scale = 2)
    private Integer totalAmount;

    @Column(nullable = false)
    private LocalDateTime bookedAt;

    @Column
    private LocalDateTime confirmedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;  // 10분 타임아웃

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Booking(Long memberId, Long eventId, String bookingNumber,
                   Integer ticketCount, Integer totalAmount, long timeoutSeconds) {
        this.memberId = memberId;
        this.eventId = eventId;
        this.bookingNumber = bookingNumber;
        this.status = BookingStatus.RESERVED;
        this.ticketCount = ticketCount;
        this.totalAmount = totalAmount;
        this.bookedAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusSeconds(timeoutSeconds);
    }



    // 비즈니스 로직
    public void confirm() {
        if (this.status != BookingStatus.RESERVED) {
            throw new IllegalStateException("Booking is not in reserved state");
        }
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (this.status == BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot cancel confirmed booking");
        }
        this.status = BookingStatus.CANCELLED;
    }

    public void expire() {
        if (this.status == BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot expire confirmed booking");
        }
        this.status = BookingStatus.EXPIRED;
    }

    public boolean isExpired() {
        if (this.status == BookingStatus.CONFIRMED || this.status == BookingStatus.CANCELLED) {
            return false;
        }
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}

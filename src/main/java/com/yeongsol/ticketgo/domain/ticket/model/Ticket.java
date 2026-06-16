package com.yeongsol.ticketgo.domain.ticket.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = true)
    private Long bookingId;

    @Column(nullable = false, unique = true, length = 36)
    private String ticketNumber;  // UUID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Ticket(Long eventId, Long bookingId, String ticketNumber) {
        this.eventId = eventId;
        this.bookingId = bookingId;
        this.ticketNumber = ticketNumber;
        this.status = TicketStatus.BOOKED;
    }

    public static Ticket preIssue(Long eventId) {
        Ticket ticket = new Ticket();
        ticket.eventId = eventId;
        ticket.ticketNumber = java.util.UUID.randomUUID().toString();
        ticket.status = TicketStatus.AVAILABLE;
        return ticket;
    }

    // 비즈니스 로직
    public void assign(Long bookingId) {
        if (this.status != TicketStatus.AVAILABLE) {
            throw new IllegalStateException("Ticket is not available");
        }
        this.bookingId = bookingId;
        this.status = TicketStatus.BOOKED;
    }

    public void cancel() {
        if (this.status == TicketStatus.CANCELLED) {
            throw new IllegalStateException("Ticket is already cancelled");
        }
        this.status = TicketStatus.CANCELLED;
    }
}

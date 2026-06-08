package com.yeongsol.ticketgo.domain.event.model;

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
@Table(name = "events")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDateTime startDateTime;

    @Column(nullable = false)
    private LocalDateTime saleStartDateTime;

    @Column(nullable = false)
    private LocalDateTime saleEndDateTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Column(nullable = false)
    private Integer totalTickets;

    @Column(nullable = false)
    private Integer availableTickets;  // 남은 티켓 수 (동시성 제어 대상)

    @Column(nullable = false)
    private Integer price;  // 한국 화폐 (원) - 소수점 불필요

    @Column(nullable = false, length = 200)
    private String venue;  // 공연장 정보

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;  // 낙관적 락용 - availableTickets 동시성 제어

    @Builder
    public Event(String name, String description,
                 LocalDateTime startDateTime, LocalDateTime saleStartDateTime, LocalDateTime saleEndDateTime,
                 EventStatus status, Integer totalTickets, Integer price, String venue) {
        this.name = name;
        this.description = description;
        this.startDateTime = startDateTime;
        this.saleStartDateTime = saleStartDateTime;
        this.saleEndDateTime = saleEndDateTime;
        this.status = status != null ? status : EventStatus.DRAFT;
        this.totalTickets = totalTickets;
        this.availableTickets = totalTickets;  // 초기에는 전체 티켓이 모두 available
        this.price = price;
        this.venue = venue;
    }

    // 비즈니스 로직

    /**
     * 판매 시작
     */
    public void startSale() {
        if (this.status != EventStatus.DRAFT) {
            throw new IllegalStateException("Event is not in DRAFT status");
        }
        this.status = EventStatus.ON_SALE;
    }

    /**
     * 이벤트 취소
     */
    public void cancel() {
        if (this.status == EventStatus.COMPLETED || this.status == EventStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel completed or already cancelled event");
        }
        this.status = EventStatus.CANCELLED;
    }

    /**
     * 이벤트 완료 처리
     */
    public void complete() {
        this.status = EventStatus.COMPLETED;
    }

    public void decreaseAvailableTickets(int count) {
        if (this.availableTickets < count) {
            throw new IllegalStateException("Not enough available tickets");
        }
        this.availableTickets -= count;
        if (this.availableTickets == 0) {
            this.status = EventStatus.SOLD_OUT;
        }
    }

    public void increaseAvailableTickets(int count) {
        this.availableTickets += count;
        if (this.status == EventStatus.SOLD_OUT && this.availableTickets > 0) {
            this.status = EventStatus.ON_SALE;
        }
    }

    public void updateStatus(EventStatus status) {
        this.status = status;
    }
}

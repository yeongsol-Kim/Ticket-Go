package com.yeongsol.ticketgo.domain.event.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Event 도메인 로직 테스트")
class EventTest {

    @Test
    @DisplayName("이벤트 생성 시 상태는 DRAFT, availableTickets는 totalTickets와 동일")
    void createEvent() {
        // Given & When
        Event event = Event.builder()
                .name("테스트 콘서트")
                .description("테스트")
                .startDateTime(LocalDateTime.now().plusDays(30))
                .saleStartDateTime(LocalDateTime.now().minusHours(1))
                .saleEndDateTime(LocalDateTime.now().plusDays(29))
                .totalTickets(100)
                .price(50000)
                .venue("테스트 공연장")
                .build();

        // Then
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getAvailableTickets()).isEqualTo(100);
        assertThat(event.getTotalTickets()).isEqualTo(100);
    }

    @Test
    @DisplayName("판매 시작: DRAFT → ON_SALE")
    void startSale() {
        // Given
        Event event = createDraftEvent();

        // When
        event.startSale();

        // Then
        assertThat(event.getStatus()).isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    @DisplayName("판매 시작 실패: DRAFT 상태가 아니면 예외 발생")
    void startSale_notDraft_throwsException() {
        // Given: 이미 ON_SALE 상태
        Event event = createDraftEvent();
        event.startSale();

        // When & Then
        assertThatThrownBy(() -> event.startSale())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Event is not in DRAFT status");
    }

    @Test
    @DisplayName("재고 감소: 정상적으로 티켓 수 감소")
    void decreaseAvailableTickets() {
        // Given
        Event event = createOnSaleEvent();

        // When
        event.decreaseAvailableTickets(10);

        // Then
        assertThat(event.getAvailableTickets()).isEqualTo(90);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    @DisplayName("재고 감소: 재고 소진 시 SOLD_OUT 상태로 변경")
    void decreaseAvailableTickets_soldOut() {
        // Given
        Event event = createOnSaleEvent();

        // When: 전체 재고 소진
        event.decreaseAvailableTickets(100);

        // Then
        assertThat(event.getAvailableTickets()).isEqualTo(0);
        assertThat(event.getStatus()).isEqualTo(EventStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("재고 감소 실패: 재고보다 많은 수량 요청 시 예외 발생")
    void decreaseAvailableTickets_notEnough_throwsException() {
        // Given
        Event event = createOnSaleEvent();

        // When & Then
        assertThatThrownBy(() -> event.decreaseAvailableTickets(101))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Not enough available tickets");
    }

    @Test
    @DisplayName("재고 증가: 정상적으로 티켓 수 증가")
    void increaseAvailableTickets() {
        // Given: 90개 재고
        Event event = createOnSaleEvent();
        event.decreaseAvailableTickets(10);

        // When
        event.increaseAvailableTickets(5);

        // Then
        assertThat(event.getAvailableTickets()).isEqualTo(95);
    }

    @Test
    @DisplayName("재고 증가: SOLD_OUT 상태에서 재고 추가 시 ON_SALE로 복귀")
    void increaseAvailableTickets_fromSoldOut() {
        // Given: SOLD_OUT 상태
        Event event = createOnSaleEvent();
        event.decreaseAvailableTickets(100);
        assertThat(event.getStatus()).isEqualTo(EventStatus.SOLD_OUT);

        // When: 재고 추가
        event.increaseAvailableTickets(10);

        // Then
        assertThat(event.getAvailableTickets()).isEqualTo(10);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    @DisplayName("이벤트 취소: COMPLETED나 CANCELLED가 아니면 취소 가능")
    void cancel() {
        // Given
        Event event = createOnSaleEvent();

        // When
        event.cancel();

        // Then
        assertThat(event.getStatus()).isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    @DisplayName("이벤트 취소 실패: COMPLETED 상태는 취소 불가")
    void cancel_completed_throwsException() {
        // Given
        Event event = createOnSaleEvent();
        event.complete();

        // When & Then
        assertThatThrownBy(() -> event.cancel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot cancel completed or already cancelled event");
    }

    @Test
    @DisplayName("이벤트 취소 실패: 이미 CANCELLED 상태는 다시 취소 불가")
    void cancel_alreadyCancelled_throwsException() {
        // Given
        Event event = createOnSaleEvent();
        event.cancel();

        // When & Then
        assertThatThrownBy(() -> event.cancel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot cancel completed or already cancelled event");
    }

    @Test
    @DisplayName("이벤트 완료 처리")
    void complete() {
        // Given
        Event event = createOnSaleEvent();

        // When
        event.complete();

        // Then
        assertThat(event.getStatus()).isEqualTo(EventStatus.COMPLETED);
    }

    // Helper methods
    private Event createDraftEvent() {
        return Event.builder()
                .name("테스트 콘서트")
                .description("테스트")
                .startDateTime(LocalDateTime.now().plusDays(30))
                .saleStartDateTime(LocalDateTime.now().minusHours(1))
                .saleEndDateTime(LocalDateTime.now().plusDays(29))
                .totalTickets(100)
                .price(50000)
                .venue("테스트 공연장")
                .build();
    }

    private Event createOnSaleEvent() {
        Event event = createDraftEvent();
        event.startSale();
        return event;
    }
}

package com.yeongsol.ticketgo.domain.event.service;

import com.yeongsol.ticketgo.domain.event.dto.CreateEventCommand;
import com.yeongsol.ticketgo.domain.event.exception.EventNotFoundException;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * EventService 단위 테스트
 * Mock을 사용하여 Repository 의존성 제거
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventService 단위 테스트")
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("이벤트 생성 - Repository.save 호출 확인")
    void createEvent() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CreateEventCommand command = CreateEventCommand.builder()
                .name("테스트 콘서트")
                .description("설명")
                .startDateTime(now.plusDays(30))
                .saleStartDateTime(now.minusHours(1))
                .saleEndDateTime(now.plusDays(29))
                .totalTickets(100)
                .price(50000)
                .venue("테스트 공연장")
                .build();

        Event mockEvent = createMockEvent(1L, EventStatus.DRAFT);
        given(eventRepository.save(any(Event.class))).willReturn(mockEvent);

        // When
        Event result = eventService.createEvent(command);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        then(eventRepository).should(times(1)).save(any(Event.class));
    }

    @Test
    @DisplayName("이벤트 조회 - 존재하는 ID로 조회 성공")
    void findById_success() {
        // Given
        Long eventId = 1L;
        Event mockEvent = createMockEvent(eventId, EventStatus.ON_SALE);
        given(eventRepository.findById(eventId)).willReturn(Optional.of(mockEvent));

        // When
        Event result = eventService.findById(eventId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(eventId);
        then(eventRepository).should(times(1)).findById(eventId);
    }

    @Test
    @DisplayName("이벤트 조회 실패 - 존재하지 않는 ID로 예외 발생")
    void findById_notFound_throwsException() {
        // Given
        Long eventId = 999L;
        given(eventRepository.findById(eventId)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> eventService.findById(eventId))
                .isInstanceOf(EventNotFoundException.class);
        then(eventRepository).should(times(1)).findById(eventId);
    }

    @Test
    @DisplayName("판매 시작 - 이벤트 상태를 ON_SALE로 변경")
    void startSale() {
        // Given
        Long eventId = 1L;
        Event mockEvent = createMockEvent(eventId, EventStatus.DRAFT);
        given(eventRepository.findById(eventId)).willReturn(Optional.of(mockEvent));

        // When
        eventService.startSale(eventId);

        // Then
        assertThat(mockEvent.getStatus()).isEqualTo(EventStatus.ON_SALE);
        then(eventRepository).should(times(1)).findById(eventId);
    }

    @Test
    @DisplayName("이벤트 취소 - 이벤트 상태를 CANCELLED로 변경")
    void cancelEvent() {
        // Given
        Long eventId = 1L;
        Event mockEvent = createMockEvent(eventId, EventStatus.ON_SALE);
        given(eventRepository.findById(eventId)).willReturn(Optional.of(mockEvent));

        // When
        eventService.cancelEvent(eventId);

        // Then
        assertThat(mockEvent.getStatus()).isEqualTo(EventStatus.CANCELLED);
        then(eventRepository).should(times(1)).findById(eventId);
    }

    @Test
    @DisplayName("만료된 이벤트 완료 처리 - 배치 작업")
    void completeExpiredEvents() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        Event event1 = createMockEvent(1L, EventStatus.ON_SALE);
        Event event2 = createMockEvent(2L, EventStatus.ON_SALE);
        List<Event> expiredEvents = List.of(event1, event2);

        List<EventStatus> excludeStatuses = List.of(
                EventStatus.COMPLETED,
                EventStatus.CANCELLED
        );

        given(eventRepository.findEventsToComplete(any(LocalDateTime.class), eq(excludeStatuses)))
                .willReturn(expiredEvents);

        // When
        eventService.completeExpiredEvents();

        // Then
        assertThat(event1.getStatus()).isEqualTo(EventStatus.COMPLETED);
        assertThat(event2.getStatus()).isEqualTo(EventStatus.COMPLETED);
        then(eventRepository).should(times(1))
                .findEventsToComplete(any(LocalDateTime.class), eq(excludeStatuses));
    }

    @Test
    @DisplayName("판매 중인 이벤트 목록 조회")
    void getOnSaleEvents() {
        // Given
        Event event1 = createMockEvent(1L, EventStatus.ON_SALE);
        Event event2 = createMockEvent(2L, EventStatus.ON_SALE);
        List<Event> onSaleEvents = List.of(event1, event2);

        given(eventRepository.findOnSaleEvents(eq(EventStatus.ON_SALE), any(LocalDateTime.class)))
                .willReturn(onSaleEvents);

        // When
        List<Event> result = eventService.getOnSaleEvents();

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(event1, event2);
        then(eventRepository).should(times(1))
                .findOnSaleEvents(eq(EventStatus.ON_SALE), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("재고 확인 - Repository 메서드 호출")
    void hasAvailableTickets() {
        // Given
        Long eventId = 1L;
        given(eventRepository.hasAvailableTickets(eventId)).willReturn(true);

        // When
        boolean result = eventService.hasAvailableTickets(eventId);

        // Then
        assertThat(result).isTrue();
        then(eventRepository).should(times(1)).hasAvailableTickets(eventId);
    }

    // Helper method
    private Event createMockEvent(Long id, EventStatus status) {
        LocalDateTime now = LocalDateTime.now();
        Event event = Event.builder()
                .name("테스트 콘서트")
                .description("테스트")
                .startDateTime(now.plusDays(30))
                .saleStartDateTime(now.minusHours(1))
                .saleEndDateTime(now.plusDays(29))
                .status(status)
                .totalTickets(100)
                .price(50000)
                .venue("테스트 공연장")
                .build();

        // Reflection으로 ID 설정 (테스트 목적)
        try {
            var idField = Event.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(event, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return event;
    }
}

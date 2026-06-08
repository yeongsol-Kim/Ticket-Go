package com.yeongsol.ticketgo.domain.event.service;

import com.yeongsol.ticketgo.domain.event.dto.CreateEventCommand;
import com.yeongsol.ticketgo.domain.event.exception.EventNotFoundException;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;

    /**
     * 이벤트 생성
     */
    @Transactional
    public Event createEvent(CreateEventCommand command) {
        Event event = Event.builder()
                .name(command.name())
                .description(command.description())
                .startDateTime(command.startDateTime())
                .saleStartDateTime(command.saleStartDateTime())
                .saleEndDateTime(command.saleEndDateTime())
                .status(EventStatus.DRAFT)
                .totalTickets(command.totalTickets())
                .price(command.price())
                .venue(command.venue())
                .build();

        return eventRepository.save(event);
    }

    /**
     * 이벤트 ID로 조회
     */
    public Event findById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(EventNotFoundException::new);
    }

    /**
     * 비관적 락으로 조회
     * 높은 동시성 상황에서 사용
     */
    @Transactional
    public Event findByIdWithPessimisticLock(Long id) {
        return eventRepository.findByIdWithPessimisticLock(id)
                .orElseThrow(EventNotFoundException::new);
    }

    /**
     * 판매 중인 이벤트 목록 조회
     */
    public List<Event> getOnSaleEvents() {
        return eventRepository.findOnSaleEvents(EventStatus.ON_SALE, LocalDateTime.now());
    }


    /**
     * 판매 예정 이벤트 조회
     */
    public List<Event> getUpcomingEvents() {
        return eventRepository.findUpcomingEvents(EventStatus.ON_SALE, LocalDateTime.now());
    }

    /**
     * 이벤트 판매 시작
     */
    @Transactional
    public void startSale(Long eventId) {
        Event event = findById(eventId);
        event.updateStatus(EventStatus.ON_SALE);
    }

    /**
     * 이벤트 취소
     */
    @Transactional
    public void cancelEvent(Long eventId) {
        Event event = findById(eventId);
        event.updateStatus(EventStatus.CANCELLED);
    }

    /**
     * 이벤트 종료 (배치 처리용)
     */
    @Transactional
    public void completeExpiredEvents() {
        List<EventStatus> excludeStatuses = List.of(
                EventStatus.COMPLETED,
                EventStatus.CANCELLED
        );

        List<Event> eventsToComplete = eventRepository.findEventsToComplete(
                LocalDateTime.now(), excludeStatuses);

        eventsToComplete.forEach(event -> event.updateStatus(EventStatus.COMPLETED));
    }

    /**
     * 재고 확인
     */
    public boolean hasAvailableTickets(Long eventId) {
        return eventRepository.hasAvailableTickets(eventId);
    }
}

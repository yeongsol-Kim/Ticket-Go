package com.yeongsol.ticketgo.domain.queue.scheduler;

import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.queue.service.AdaptivePollingPolicy;
import com.yeongsol.ticketgo.domain.queue.service.QueueNotifier;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueScheduler 입장 시작 지연 단위 테스트")
class QueueSchedulerTest {

    private static final Long EVENT_ID = 3L;

    @Mock private QueueService queueService;
    @Mock private EventRepository eventRepository;
    @Mock private BookingService bookingService;
    @Mock private AdaptivePollingPolicy pollingPolicy;
    @Mock private QueueNotifier queueNotifier;

    private QueueScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new QueueScheduler(queueService, eventRepository, bookingService,
                new SimpleMeterRegistry(), pollingPolicy, queueNotifier);
        ReflectionTestUtils.setField(scheduler, "admitCountPerCycle", 32);
        ReflectionTestUtils.setField(scheduler, "maxBookingRetry", 3);

        Event event = mock(Event.class);
        given(event.getId()).willReturn(EVENT_ID);
        given(event.getAvailableTickets()).willReturn(10_000);
        given(eventRepository.findByStatus(EventStatus.ON_SALE)).willReturn(List.of(event));
    }

    @Test
    @DisplayName("지연이 0이면 대기열이 열리자마자 입장 처리한다 (기존 동작)")
    void noDelay_admitsImmediately() {
        given(queueService.getQueueSize(EVENT_ID)).willReturn(100L);
        given(queueService.peekNext(EVENT_ID, 32)).willReturn(List.of());

        scheduler.processQueueAdmission();

        verify(queueService).peekNext(EVENT_ID, 32);
        verify(pollingPolicy, never()).markAdmissionStart(anyLong(), anyLong());
    }

    @Test
    @DisplayName("지연 중엔 줄만 세우고 입장시키지 않으며, 예상 대기 계산에 입장 시작 시각을 알린다")
    void withinDelay_holdsAdmission() {
        ReflectionTestUtils.setField(scheduler, "admissionStartDelayMs", 60_000L);
        given(queueService.getQueueSize(EVENT_ID)).willReturn(100L);

        scheduler.processQueueAdmission();
        scheduler.processQueueAdmission();   // 여러 사이클이 지나도 지연 중이면 계속 보류

        verify(queueService, never()).peekNext(eq(EVENT_ID), anyInt());
        verify(pollingPolicy, times(1)).markAdmissionStart(eq(EVENT_ID), anyLong());   // 오픈 시각은 한 번만 기록
    }

    @Test
    @DisplayName("대기열이 비면 오픈 시각을 지워, 다음 판매는 다시 처음부터 지연한다")
    void emptyQueue_resetsOpenedAt() {
        ReflectionTestUtils.setField(scheduler, "admissionStartDelayMs", 60_000L);
        given(queueService.getQueueSize(EVENT_ID)).willReturn(100L, 0L, 50L);

        scheduler.processQueueAdmission();   // 오픈 → 지연 시작
        scheduler.processQueueAdmission();   // 비었음 → 초기화
        scheduler.processQueueAdmission();   // 다시 오픈 → 지연 다시 시작

        verify(pollingPolicy).clearAdmissionStart(EVENT_ID);
        verify(pollingPolicy, times(2)).markAdmissionStart(eq(EVENT_ID), anyLong());
        verify(queueService, never()).peekNext(eq(EVENT_ID), anyInt());
    }
}

package com.yeongsol.ticketgo.domain.queue.service;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;
import com.yeongsol.ticketgo.domain.queue.dto.QueueDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueNotifier (SSE 입장 알림) 단위 테스트")
class QueueNotifierTest {

    private static final Long EVENT_ID = 3L;
    private static final Long MEMBER_ID = 7L;

    @Mock
    private QueueService queueService;

    @Mock
    private AdaptivePollingPolicy pollingPolicy;

    private QueueStreamPolicy streamPolicy;
    private QueueNotifier notifier;

    @BeforeEach
    void setUp() {
        streamPolicy = new QueueStreamPolicy(pollingPolicy);
        ReflectionTestUtils.setField(streamPolicy, "maxConnections", 1500);
        ReflectionTestUtils.setField(streamPolicy, "switchWaitSeconds", 10.0);
        // 전송 스레드풀 대신 호출 스레드에서 바로 실행 → 결과를 즉시 검증
        notifier = new QueueNotifier(queueService, streamPolicy, Runnable::run, new SimpleMeterRegistry());
    }

    private static QueueDto.StatusResponse waiting(int position) {
        return QueueDto.StatusResponse.builder().eventId(EVENT_ID).memberId(MEMBER_ID)
                .isActive(false).position(position).build();
    }

    private static QueueDto.StatusResponse admitted(Long bookingId) {
        return QueueDto.StatusResponse.builder().eventId(EVENT_ID).memberId(MEMBER_ID)
                .isActive(true).bookingId(bookingId).build();
    }

    /** emitter.send()로 보낸 이벤트들의 이름 (event:xxx 줄) */
    private static List<String> sentEventNames(SseEmitter emitter, int times) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(times)).send(captor.capture());
        return captor.getAllValues().stream()
                .map(b -> b.build().stream().map(d -> String.valueOf(d.getData())).collect(Collectors.joining()))
                .map(text -> text.lines().filter(l -> l.startsWith("event:")).findFirst().orElse("(comment)"))
                .toList();
    }

    @Test
    @DisplayName("대기 중에 구독하면 현재 상태(status)를 1회 보내고 연결을 유지한다")
    void subscribe_waiting_sendsStatus() throws IOException {
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willReturn(waiting(120));
        SseEmitter emitter = mock(SseEmitter.class);

        notifier.subscribe(EVENT_ID, MEMBER_ID, emitter);

        assertThat(sentEventNames(emitter, 1)).containsExactly("event:status");
        verify(emitter, never()).complete();
        assertThat(notifier.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 입장한 상태로 구독하면 admitted를 보내고 닫는다")
    void subscribe_alreadyAdmitted_sendsAdmittedAndCompletes() throws IOException {
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willReturn(admitted(77L));
        SseEmitter emitter = mock(SseEmitter.class);

        notifier.subscribe(EVENT_ID, MEMBER_ID, emitter);

        assertThat(sentEventNames(emitter, 1)).containsExactly("event:admitted");
        verify(emitter).complete();
        // 반환 전 complete()는 onCompletion 콜백이 안 오므로 직접 해제해야 한다 (로컬 실측으로 발견한 누수)
        assertThat(notifier.connectionCount()).isZero();
    }

    @Test
    @DisplayName("대기열에도 세션에도 없으면 not-in-queue를 보내고 닫는다")
    void subscribe_notInQueue_sendsNotInQueueAndCompletes() throws IOException {
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willThrow(new BusinessException(ErrorCode.NOT_IN_QUEUE));
        SseEmitter emitter = mock(SseEmitter.class);

        notifier.subscribe(EVENT_ID, MEMBER_ID, emitter);

        assertThat(sentEventNames(emitter, 1)).containsExactly("event:not-in-queue");
        verify(emitter).complete();
        assertThat(notifier.connectionCount()).isZero();
    }

    @Test
    @DisplayName("입장 알림은 구독 중인 사용자에게만 admitted를 보내고 그 연결을 닫는다")
    void notifyAdmitted_sendsOnlyToSubscribers() throws IOException {
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willReturn(waiting(1));
        SseEmitter emitter = mock(SseEmitter.class);
        notifier.subscribe(EVENT_ID, MEMBER_ID, emitter);

        notifier.notifyAdmitted(EVENT_ID, List.of(
                new QueueService.SessionGrant(MEMBER_ID, 77L),
                new QueueService.SessionGrant(999L, 78L)));   // SSE 미구독자 → 무시 (폴링으로 알게 됨)

        assertThat(sentEventNames(emitter, 2)).containsExactly("event:status", "event:admitted");
        verify(emitter).complete();
        assertThat(notifier.connectionCount()).isZero();
    }

    @Test
    @DisplayName("등록 먼저 → 상태 조회: 조회 도중 입장해도 알림을 놓치지 않는다")
    void subscribe_admittedDuringStatusLookup_stillNotified() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        // 상태를 읽는 사이 스케줄러가 입장 처리 → 조회 결과는 "대기 중"(이미 지난 값)
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willAnswer(inv -> {
            notifier.notifyAdmitted(EVENT_ID, List.of(new QueueService.SessionGrant(MEMBER_ID, 77L)));
            return waiting(1);
        });

        notifier.subscribe(EVENT_ID, MEMBER_ID, emitter);

        assertThat(sentEventNames(emitter, 2)).contains("event:admitted");
    }

    @Test
    @DisplayName("연결이 끝나면(onCompletion) 등록이 풀려 이후 알림을 보내지 않는다")
    void completion_removesSubscription() throws IOException {
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willReturn(waiting(5));
        SseEmitter emitter = mock(SseEmitter.class);
        notifier.subscribe(EVENT_ID, MEMBER_ID, emitter);

        ArgumentCaptor<Runnable> onCompletion = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(onCompletion.capture());
        onCompletion.getValue().run();

        notifier.notifyAdmitted(EVENT_ID, List.of(new QueueService.SessionGrant(MEMBER_ID, 77L)));

        assertThat(notifier.connectionCount()).isZero();
        assertThat(sentEventNames(emitter, 1)).containsExactly("event:status");
    }

    @Test
    @DisplayName("재연결하면 이전 연결을 닫고 새 연결로 알린다 (재연결 중 연결 수는 1)")
    void resubscribe_replacesPreviousEmitter() throws IOException {
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willReturn(waiting(5));
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);

        notifier.subscribe(EVENT_ID, MEMBER_ID, first);
        notifier.subscribe(EVENT_ID, MEMBER_ID, second);
        assertThat(notifier.connectionCount()).isEqualTo(1);

        notifier.notifyAdmitted(EVENT_ID, List.of(new QueueService.SessionGrant(MEMBER_ID, 77L)));

        verify(first).complete();
        verify(first, times(1)).send(any(SseEmitter.SseEventBuilder.class));   // 이전 연결엔 admitted가 안 간다
        assertThat(sentEventNames(second, 2)).containsExactly("event:status", "event:admitted");
        assertThat(notifier.connectionCount()).isZero();
    }

    @Test
    @DisplayName("클라이언트가 끊겨 쓰기에 실패하면 예외를 밖으로 던지지 않고 연결을 닫는다")
    void sendFailure_isSwallowedAndCompletes() throws IOException {
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willReturn(waiting(5));
        SseEmitter emitter = mock(SseEmitter.class);
        notifier.subscribe(EVENT_ID, MEMBER_ID, emitter);
        willThrow(new IOException("Broken pipe")).given(emitter).send(any(SseEmitter.SseEventBuilder.class));

        notifier.notifyAdmitted(EVENT_ID, List.of(new QueueService.SessionGrant(MEMBER_ID, 77L)));

        verify(emitter, times(2)).complete();   // send 실패 시 1회 + 닫기 1회 (중복 complete는 무해)
        assertThat(notifier.connectionCount()).isZero();
    }

    @Test
    @DisplayName("연결 상한이면 use-polling을 보내고 등록하지 않는다 (메모리 보호, 클라이언트는 폴링 계속)")
    void subscribe_overCapacity_rejectsWithUsePolling() throws IOException {
        ReflectionTestUtils.setField(streamPolicy, "maxConnections", 1);
        given(queueService.getQueueStatus(EVENT_ID, MEMBER_ID)).willReturn(waiting(5));
        notifier.subscribe(EVENT_ID, MEMBER_ID, mock(SseEmitter.class));   // 1개로 상한 도달

        SseEmitter rejected = mock(SseEmitter.class);
        notifier.subscribe(EVENT_ID, 8L, rejected);

        assertThat(sentEventNames(rejected, 1)).containsExactly("event:use-polling");
        verify(rejected).complete();
        assertThat(notifier.connectionCount()).isEqualTo(1);
    }
}

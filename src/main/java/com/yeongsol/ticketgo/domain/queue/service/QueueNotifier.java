package com.yeongsol.ticketgo.domain.queue.service;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.domain.queue.dto.QueueDto;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 대기열 입장 알림 (SSE) - 스케줄러가 결제 세션을 발급한 직후 해당 사용자에게 바로 알린다.
 *
 * 3차 부하테스트 발견: 폴링 방식은 "폴링 수 ↔ 입장 통보 지연"을 맞바꿀 뿐이라
 * (폴링 1.1만/지연 30초 ~ 6.1만/1.3초), 서버가 먼저 알려 이 맞바꿈을 없앤다.
 *
 * - 순번 갱신은 보내지 않는다: 1만 명에게 주기적으로 보내면 그 자체가 폴링만큼 비싸다.
 *   연결 직후 현재 상태 1회 + 입장(admitted) 1회 + heartbeat만 보낸다.
 * - 등록 먼저 → 상태 조회: 반대 순서면 조회 후 등록 전 틈에 입장한 사람은 알림을 영영 못 받는다.
 *   대신 admitted가 두 번 갈 수 있다 (클라이언트는 첫 번째만 쓰면 된다).
 * - 전송은 별도 스레드풀: 느린 클라이언트 쓰기가 스케줄러 스레드(Spring 기본 1개)를 막지 않게.
 * - 앱 1대 기준 인메모리. 다중 인스턴스면 Redis pub/sub 등으로 입장 이벤트를 공유해야 한다.
 * - 동시 연결 수는 QueueStreamPolicy가 상한을 건다 (연결당 ~100KB). 상한이면 use-polling을 보내고 닫는다.
 */
@Slf4j
@Component
public class QueueNotifier {

    public static final String EVENT_STATUS = "status";
    public static final String EVENT_ADMITTED = "admitted";
    public static final String EVENT_NOT_IN_QUEUE = "not-in-queue";
    public static final String EVENT_USE_POLLING = "use-polling";

    private final QueueService queueService;
    private final QueueStreamPolicy streamPolicy;
    private final Executor sendExecutor;

    private final Map<Long, Map<Long, SseEmitter>> emitters = new ConcurrentHashMap<>();  // eventId → memberId → emitter

    @Autowired
    public QueueNotifier(QueueService queueService, QueueStreamPolicy streamPolicy, MeterRegistry meterRegistry) {
        // Executor를 빈으로 등록하면 Spring Boot 기본 applicationTaskExecutor가 빠지므로 내부에서 만든다
        this(queueService, streamPolicy, Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "sse-send");
            t.setDaemon(true);
            return t;
        }), meterRegistry);
    }

    QueueNotifier(QueueService queueService, QueueStreamPolicy streamPolicy, Executor sendExecutor,
                  MeterRegistry meterRegistry) {
        this.queueService = queueService;
        this.streamPolicy = streamPolicy;
        this.sendExecutor = sendExecutor;
        Gauge.builder("queue_sse_connections", streamPolicy, QueueStreamPolicy::connections)
                .description("열려 있는 대기열 SSE 연결 수")
                .register(meterRegistry);
    }

    /**
     * 연결 등록 후 현재 상태를 1회 보낸다 (이미 입장했으면 admitted)
     */
    public void subscribe(Long eventId, Long memberId, SseEmitter emitter) {
        Map<Long, SseEmitter> byMember = emitters.computeIfAbsent(eventId, k -> new ConcurrentHashMap<>());
        boolean reconnect = byMember.containsKey(memberId);
        if (!reconnect && !streamPolicy.tryOpen()) {
            // 연결 상한: 메모리 보호를 위해 거절 → 클라이언트는 폴링을 계속한다
            send(emitter, SseEmitter.event().name(EVENT_USE_POLLING).data("too many streams"));
            completeQuietly(emitter);
            return;
        }
        SseEmitter previous = byMember.put(memberId, emitter);
        if (previous != null) {
            if (!reconnect) streamPolicy.closed();   // 동시 첫 연결 경합: 자리를 두 번 잡았으니 하나 반납
            completeQuietly(previous);               // 재연결: 이전 연결은 닫는다
        }

        Runnable cleanup = () -> remove(eventId, memberId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            QueueDto.StatusResponse status = queueService.getQueueStatus(eventId, memberId);
            if (Boolean.TRUE.equals(status.getIsActive())) {
                sendAdmitted(eventId, memberId, status.getBookingId());
            } else {
                send(emitter, SseEmitter.event().name(EVENT_STATUS).data(status));
            }
        } catch (BusinessException e) {
            // 대기열에도 없고 세션도 없음 (매진 등) → 알리고 닫는다
            send(emitter, SseEmitter.event().name(EVENT_NOT_IN_QUEUE).data(e.getMessage()));
            close(eventId, memberId, emitter);
        }
    }

    /**
     * 스케줄러가 세션 발급(파이프라인) 성공 직후 호출
     */
    public void notifyAdmitted(Long eventId, List<QueueService.SessionGrant> grants) {
        Map<Long, SseEmitter> byMember = emitters.get(eventId);
        if (byMember == null || byMember.isEmpty()) return;

        sendExecutor.execute(() -> {
            for (QueueService.SessionGrant g : grants) {
                sendAdmitted(eventId, g.memberId(), g.bookingId());
            }
        });
    }

    /**
     * 연결 유지 + 끊긴 연결 정리 (쓰기에 실패한 연결은 등록 해제)
     */
    @Scheduled(fixedDelayString = "${queue.sse.heartbeat-ms:20000}")
    public void heartbeat() {
        if (streamPolicy.connections() == 0) return;
        sendExecutor.execute(() -> emitters.forEach((eventId, byMember) ->
                byMember.forEach((memberId, emitter) -> {
                    if (!send(emitter, SseEmitter.event().comment("hb"))) remove(eventId, memberId, emitter);
                })));
    }

    public int connectionCount() {
        return streamPolicy.connections();
    }

    private void sendAdmitted(Long eventId, Long memberId, Long bookingId) {
        Map<Long, SseEmitter> byMember = emitters.get(eventId);
        SseEmitter emitter = byMember != null ? byMember.get(memberId) : null;
        if (emitter == null) return;   // SSE로 안 붙은 사용자 → 폴링으로 알게 된다

        QueueDto.AdmittedEvent payload = new QueueDto.AdmittedEvent(eventId, bookingId, System.currentTimeMillis());
        send(emitter, SseEmitter.event().name(EVENT_ADMITTED).data(payload));
        close(eventId, memberId, emitter);   // 할 일 끝: 클라이언트는 결제로 넘어간다 (전송 실패여도 닫는다)
    }

    /**
     * 등록 해제 + 닫기. onCompletion 콜백에만 맡기지 않는다:
     * 컨트롤러가 emitter를 반환하기 전(연결 처리 중)에 complete()하면 콜백이 오지 않아
     * 연결 수가 새는 것을 로컬 실측으로 확인 (재연결·not-in-queue 경로)
     */
    private void close(Long eventId, Long memberId, SseEmitter emitter) {
        remove(eventId, memberId, emitter);
        completeQuietly(emitter);
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 이미 닫힘
        }
    }

    private boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            // 클라이언트가 끊었거나 이미 완료된 emitter. 쓰기 실패면 컨테이너가 onError로 정리하지만,
            // 콜백이 안 오는 경우를 대비해 닫아 둔다 (쓰기 실패 후 complete()는 Spring이 무시)
            completeQuietly(emitter);
            return false;
        }
    }

    private void remove(Long eventId, Long memberId, SseEmitter emitter) {
        Map<Long, SseEmitter> byMember = emitters.get(eventId);
        if (byMember != null && byMember.remove(memberId, emitter)) {
            streamPolicy.closed();
        }
    }
}

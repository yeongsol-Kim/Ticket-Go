package com.yeongsol.ticketgo.domain.queue.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 하이브리드 대기 정책 - 차례가 먼 사람은 적응형 폴링, 가까워지면 SSE로 갈아탄다.
 *
 * 4차 실측: Tomcat 서블릿 SSE는 연결 1개가 대기 내내 요청 버퍼를 붙잡아 ~100KB를 쓴다
 * (연결 988개에 힙 +100MB). 1만 명 전원을 SSE로 붙이면 ~1GB라 기본 힙(~500MB)에서 OOM.
 * 3차에서 폴링 비용의 대부분은 차례가 가까운 사람들의 잦은 폴링이었으므로, 그 구간만 SSE로 맡긴다.
 *   - 예상 대기 ≤ switch-wait-seconds 이면 응답에 streamRecommended=true → 클라이언트가 SSE 구독
 *   - 동시 연결은 max-connections로 상한 (넘으면 SSE 거절 → 클라이언트는 폴링 계속)
 *   - switch-wait-seconds=0 이면 SSE 권유를 끈다 (순수 폴링, 3차와 같은 동작)
 */
@Component
@RequiredArgsConstructor
public class QueueStreamPolicy {

    private final AdaptivePollingPolicy pollingPolicy;

    @Value("${queue.sse.switch-wait-seconds:10}")
    private double switchWaitSeconds;

    @Value("${queue.sse.max-connections:1500}")
    private int maxConnections;       // 연결당 ~100KB → 1500개 ≈ 150MB

    private final AtomicInteger connections = new AtomicInteger();

    /**
     * 대기 중인 사람에게 SSE로 갈아타라고 권할지
     */
    public boolean shouldStream(Long eventId, int position) {
        if (switchWaitSeconds <= 0) return false;
        if (connections.get() >= maxConnections) return false;
        return pollingPolicy.expectedWaitSeconds(eventId, position) <= switchWaitSeconds;
    }

    /**
     * 연결 자리 하나 확보 (상한이면 false)
     */
    public boolean tryOpen() {
        while (true) {
            int current = connections.get();
            if (current >= maxConnections) return false;
            if (connections.compareAndSet(current, current + 1)) return true;
        }
    }

    public void closed() {
        connections.decrementAndGet();
    }

    public int connections() {
        return connections.get();
    }
}

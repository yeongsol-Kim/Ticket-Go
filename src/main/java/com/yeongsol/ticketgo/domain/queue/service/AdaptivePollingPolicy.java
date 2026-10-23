package com.yeongsol.ticketgo.domain.queue.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 적응형 폴링 정책 - 순번과 최근 입장 속도로 "다음 폴링까지 몇 초"를 정한다.
 *
 * 3차 부하테스트 발견:
 *   - 고정 2초 폴링: 서버 포화(CPU 100%)라 폴링이 서버 처리 시간의 절반을 차지 → 승인 지연
 *   - 고정 15초 폴링: 폴링은 반감했지만 입장 후 최대 15초 통보 공백 +
 *     시작 직후 빈 구간에 몰린 입장 → 붐비는 시간에 결제 쏠림 → p95 악화
 *   → 차례가 먼 사람은 드물게, 가까운 사람은 자주 묻게 한다.
 *
 * 다음 폴링 = clamp(예상 대기 × ratio, min, max),  예상 대기 = 순번 ÷ 입장속도
 *   ratio: 예상 대기의 일부만 쉰다 → 입장 속도가 빨라져도 크게 늦지 않음
 *     - 예상 대기가 길면 ratio-far(높게): 물을 때마다 남은 대기가 ratio배로 줄어드니 폴링 횟수를 정한다
 *     - 예상 대기가 짧으면 ratio-near(낮게): 마지막 간격이 곧 "입장했는데 모르는 시간"이라 통보 지연을 정한다
 *     - 그 사이(near-wait ~ far-wait초)는 선형 보간
 *   입장속도 = max(실측, min-rate)
 *     진입 폭주 중엔 스케줄러가 CPU를 뺏겨 실측이 평소보다 낮다 → 그대로 쓰면 전원 늦게 온다 (3차 실측: 1인 1폴링,
 *     통보 지연 중앙 30~45초). 낮게 잡으면 늦고(통보 지연) 높게 잡으면 일찍 와서 폴링 한 번 더일 뿐이라 하한을 둔다.
 *
 * 입장 속도는 스케줄러가 매 사이클 record()로 남긴 최근 window 동안의 실측값.
 * 앱 1대 기준 인메모리 (다중 인스턴스면 Redis 등으로 공유 필요).
 * 폴링 요청마다 Redis 왕복을 늘리지 않으려는 선택 (병목이 CPU라서).
 */
@Component
public class AdaptivePollingPolicy {

    @Value("${queue.polling.ratio-near:0.5}")
    private double ratioNear;

    @Value("${queue.polling.ratio-far:0.5}")
    private double ratioFar;

    @Value("${queue.polling.near-wait-seconds:10}")
    private double nearWaitSeconds;      // 예상 대기가 이 이하면 ratio-near

    @Value("${queue.polling.far-wait-seconds:60}")
    private double farWaitSeconds;       // 예상 대기가 이 이상이면 ratio-far

    @Value("${queue.polling.min-rate:0}")
    private double minRate;              // 입장 속도 하한(명/초). 0이면 실측 그대로

    @Value("${queue.polling.min-seconds:2}")
    private int minSeconds;

    @Value("${queue.polling.max-seconds:20}")
    private int maxSeconds;

    @Value("${queue.polling.fallback-rate:100}")
    private double fallbackRate;         // 입장 기록이 아직 없을 때 쓰는 입장 속도(명/초)

    @Value("${queue.polling.rate-window-ms:10000}")
    private long rateWindowMs;

    private final Map<Long, Deque<long[]>> admissions = new ConcurrentHashMap<>();  // eventId → [시각ms, 인원]
    private final Map<Long, Double> rates = new ConcurrentHashMap<>();             // eventId → 최근 입장 속도(명/초)

    /**
     * 스케줄러가 사이클마다 호출 (스케줄러 스레드 1개라 쓰기 경합 없음, 읽기는 rates만)
     */
    public void record(Long eventId, int admitted) {
        record(eventId, admitted, System.currentTimeMillis());
    }

    void record(Long eventId, int admitted, long nowMs) {
        Deque<long[]> window = admissions.computeIfAbsent(eventId, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(new long[]{nowMs, admitted});
            while (window.peekFirst()[0] < nowMs - rateWindowMs) {
                window.pollFirst();
            }
            long elapsedMs = nowMs - window.peekFirst()[0];
            if (elapsedMs < 1000) return;   // 1초 미만이면 속도 추정 보류 (fallback 유지)

            long sum = 0;
            for (long[] a : window) sum += a[1];
            sum -= window.peekFirst()[1];   // 구간 시작점의 입장분은 elapsed 이전에 일어난 것
            rates.put(eventId, sum * 1000.0 / elapsedMs);
        }
    }

    public int nextPollSeconds(Long eventId, int position) {
        double rate = Math.max(rates.getOrDefault(eventId, fallbackRate), minRate);
        if (rate <= 0) return maxSeconds;
        double expectedWait = position / rate;
        double seconds = expectedWait * ratioFor(expectedWait);
        return (int) Math.max(minSeconds, Math.min(maxSeconds, Math.round(seconds)));
    }

    private double ratioFor(double expectedWait) {
        if (expectedWait <= nearWaitSeconds) return ratioNear;
        if (expectedWait >= farWaitSeconds) return ratioFar;
        double t = (expectedWait - nearWaitSeconds) / (farWaitSeconds - nearWaitSeconds);
        return ratioNear + (ratioFar - ratioNear) * t;
    }
}

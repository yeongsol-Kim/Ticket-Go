package com.yeongsol.ticketgo.domain.queue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdaptivePollingPolicy 단위 테스트")
class AdaptivePollingPolicyTest {

    private static final Long EVENT_ID = 1L;
    private AdaptivePollingPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AdaptivePollingPolicy();
        ReflectionTestUtils.setField(policy, "ratioNear", 0.5);
        ReflectionTestUtils.setField(policy, "ratioFar", 0.5);
        ReflectionTestUtils.setField(policy, "nearWaitSeconds", 10.0);
        ReflectionTestUtils.setField(policy, "farWaitSeconds", 60.0);
        ReflectionTestUtils.setField(policy, "minRate", 0.0);
        ReflectionTestUtils.setField(policy, "minSeconds", 2);
        ReflectionTestUtils.setField(policy, "maxSeconds", 20);
        ReflectionTestUtils.setField(policy, "fallbackRate", 100.0);
        ReflectionTestUtils.setField(policy, "rateWindowMs", 10_000L);
    }

    @Test
    @DisplayName("입장 기록이 없으면 fallback 속도로 계산 - 순번 1000, 100명/초 → 10초 × 0.5 = 5초")
    void noRecord_usesFallbackRate() {
        assertThat(policy.nextPollSeconds(EVENT_ID, 1000)).isEqualTo(5);
    }

    @Test
    @DisplayName("차례가 가까우면 최소 간격(2초)")
    void nearFront_clampedToMin() {
        assertThat(policy.nextPollSeconds(EVENT_ID, 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("차례가 멀면 최대 간격(20초)")
    void farBack_clampedToMax() {
        assertThat(policy.nextPollSeconds(EVENT_ID, 100_000)).isEqualTo(20);
    }

    @Test
    @DisplayName("실측 입장 속도 반영 - 0.2초마다 20명 = 100명/초 → 순번 2000이면 20초 × 0.5 = 10초")
    void measuredRate_isUsed() {
        for (int i = 0; i <= 10; i++) {
            policy.record(EVENT_ID, 20, i * 200L);
        }
        // 2초 동안 (시작점 제외) 10사이클 × 20명 = 200명 → 100명/초
        assertThat(policy.nextPollSeconds(EVENT_ID, 2000)).isEqualTo(10);
    }

    @Test
    @DisplayName("입장 속도가 빨라지면 간격이 짧아진다 - 400명/초 → 순번 2000이면 5초 × 0.5 ≈ 3초")
    void fasterRate_shorterInterval() {
        for (int i = 0; i <= 10; i++) {
            policy.record(EVENT_ID, 80, i * 200L);
        }
        assertThat(policy.nextPollSeconds(EVENT_ID, 2000)).isEqualTo(3);
    }

    @Test
    @DisplayName("window 밖의 오래된 기록은 버린다 - 빨랐던 과거가 아니라 최근 속도 기준")
    void oldRecords_dropped() {
        for (int i = 0; i <= 10; i++) {
            policy.record(EVENT_ID, 200, i * 200L);           // 0~2초: 1000명/초
        }
        for (int i = 0; i <= 60; i++) {
            policy.record(EVENT_ID, 20, 20_000L + i * 200L);  // 20~32초: 100명/초
        }
        assertThat(policy.nextPollSeconds(EVENT_ID, 2000)).isEqualTo(10);
    }

    @Test
    @DisplayName("입장이 멈추면(속도 0) 최대 간격")
    void zeroRate_maxInterval() {
        for (int i = 0; i <= 10; i++) {
            policy.record(EVENT_ID, 0, i * 200L);
        }
        assertThat(policy.nextPollSeconds(EVENT_ID, 10)).isEqualTo(20);
    }

    @Test
    @DisplayName("가중 ratio - 예상 대기 짧으면 ratio-near: 순번 500, 100명/초 → 5초 × 0.5 ≈ 3초")
    void weightedRatio_near() {
        useWeighted();
        assertThat(policy.nextPollSeconds(EVENT_ID, 500)).isEqualTo(3);
    }

    @Test
    @DisplayName("가중 ratio - 예상 대기 길면 ratio-far: 순번 10000, 100명/초 → 100초 × 0.8 = 80초")
    void weightedRatio_far() {
        useWeighted();
        assertThat(policy.nextPollSeconds(EVENT_ID, 10_000)).isEqualTo(80);
    }

    @Test
    @DisplayName("가중 ratio - 그 사이는 선형 보간: 예상 대기 35초 → ratio 0.65 → 22.75 ≈ 23초")
    void weightedRatio_interpolated() {
        useWeighted();
        assertThat(policy.nextPollSeconds(EVENT_ID, 3500)).isEqualTo(23);
    }

    @Test
    @DisplayName("속도 하한 - 진입 폭주로 실측이 50명/초로 낮아도 하한 100명/초로 계산 (늦게 오는 것 방지)")
    void minRate_floorsMeasuredRate() {
        ReflectionTestUtils.setField(policy, "minRate", 100.0);
        for (int i = 0; i <= 10; i++) {
            policy.record(EVENT_ID, 10, i * 200L);   // 50명/초
        }
        // 하한 없으면 2000 ÷ 50 × 0.5 = 20초, 하한 적용 시 2000 ÷ 100 × 0.5 = 10초
        assertThat(policy.nextPollSeconds(EVENT_ID, 2000)).isEqualTo(10);
    }

    @Test
    @DisplayName("속도 하한 - 실측이 하한보다 빠르면 실측 사용: 400명/초 → 순번 2000이면 5초 × 0.5 ≈ 3초")
    void minRate_measuredFasterWins() {
        ReflectionTestUtils.setField(policy, "minRate", 100.0);
        for (int i = 0; i <= 10; i++) {
            policy.record(EVENT_ID, 80, i * 200L);
        }
        assertThat(policy.nextPollSeconds(EVENT_ID, 2000)).isEqualTo(3);
    }

    private void useWeighted() {
        ReflectionTestUtils.setField(policy, "ratioNear", 0.5);
        ReflectionTestUtils.setField(policy, "ratioFar", 0.8);
        ReflectionTestUtils.setField(policy, "maxSeconds", 150);
    }
}

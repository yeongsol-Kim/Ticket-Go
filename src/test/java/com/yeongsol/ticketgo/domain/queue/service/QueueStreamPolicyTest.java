package com.yeongsol.ticketgo.domain.queue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueStreamPolicy (폴링 ↔ SSE 하이브리드) 단위 테스트")
class QueueStreamPolicyTest {

    private static final Long EVENT_ID = 3L;

    @Mock
    private AdaptivePollingPolicy pollingPolicy;

    private QueueStreamPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new QueueStreamPolicy(pollingPolicy);
        ReflectionTestUtils.setField(policy, "switchWaitSeconds", 10.0);
        ReflectionTestUtils.setField(policy, "maxConnections", 2);
    }

    @Test
    @DisplayName("예상 대기가 전환 기준 이하면 SSE를 권하고, 넘으면 폴링을 유지한다")
    void shouldStream_byExpectedWait() {
        given(pollingPolicy.expectedWaitSeconds(EVENT_ID, 850)).willReturn(10.0);
        given(pollingPolicy.expectedWaitSeconds(EVENT_ID, 900)).willReturn(10.6);

        assertThat(policy.shouldStream(EVENT_ID, 850)).isTrue();
        assertThat(policy.shouldStream(EVENT_ID, 900)).isFalse();
    }

    @Test
    @DisplayName("연결이 상한에 차면 차례가 가까워도 SSE를 권하지 않는다")
    void shouldStream_falseWhenFull() {
        policy.tryOpen();
        policy.tryOpen();

        assertThat(policy.shouldStream(EVENT_ID, 1)).isFalse();
    }

    @Test
    @DisplayName("전환 기준이 0이면 SSE 권유를 끈다 (순수 폴링)")
    void shouldStream_disabled() {
        ReflectionTestUtils.setField(policy, "switchWaitSeconds", 0.0);

        assertThat(policy.shouldStream(EVENT_ID, 1)).isFalse();
    }

    @Test
    @DisplayName("자리는 상한까지만 잡히고, 반납하면 다시 잡을 수 있다")
    void tryOpen_respectsCapacity() {
        assertThat(policy.tryOpen()).isTrue();
        assertThat(policy.tryOpen()).isTrue();
        assertThat(policy.tryOpen()).isFalse();

        policy.closed();

        assertThat(policy.connections()).isEqualTo(1);
        assertThat(policy.tryOpen()).isTrue();
    }
}

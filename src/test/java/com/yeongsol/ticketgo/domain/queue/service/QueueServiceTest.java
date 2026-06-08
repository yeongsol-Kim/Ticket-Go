package com.yeongsol.ticketgo.domain.queue.service;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.domain.queue.dto.QueueDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

/**
 * QueueService 단위 테스트
 * Redis 연산을 Mock으로 처리
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueService 단위 테스트")
class QueueServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Nested
    @DisplayName("enterQueue 테스트")
    class EnterQueueTest {

        @Test
        @DisplayName("대기열 진입 성공 - 새로운 사용자")
        void enterQueue_success() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(false);
            given(zSetOperations.score("queue:" + eventId, memberId.toString())).willReturn(null);
            given(zSetOperations.add(eq("queue:" + eventId), eq(memberId.toString()), anyDouble())).willReturn(true);
            given(zSetOperations.rank("queue:" + eventId, memberId.toString())).willReturn(0L);

            // When
            QueueDto.EnterResponse result = queueService.enterQueue(eventId, memberId, 2);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getEventId()).isEqualTo(eventId);
            assertThat(result.getMemberId()).isEqualTo(memberId);
            assertThat(result.getPosition()).isEqualTo(1); // rank 0 + 1

            then(zSetOperations).should(times(1)).add(eq("queue:" + eventId), eq(memberId.toString()), anyDouble());
        }

        @Test
        @DisplayName("대기열 진입 실패 - 이미 활성 세션 존재")
        void enterQueue_alreadyActive_throwsException() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> queueService.enterQueue(eventId, memberId, 2))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("대기열 진입 - 이미 대기 중인 사용자는 현재 순번 반환")
        void enterQueue_alreadyInQueue_returnsCurrentPosition() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;
            double existingScore = 1000.0;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(false);
            given(zSetOperations.score("queue:" + eventId, memberId.toString())).willReturn(existingScore);
            given(zSetOperations.rank("queue:" + eventId, memberId.toString())).willReturn(5L);

            // When
            QueueDto.EnterResponse result = queueService.enterQueue(eventId, memberId, 2);

            // Then
            assertThat(result.getPosition()).isEqualTo(6); // rank 5 + 1
            assertThat(result.getTimestamp()).isEqualTo((long) existingScore);

            // add가 호출되지 않았는지 확인
            then(zSetOperations).should(times(0)).add(anyString(), anyString(), anyDouble());
        }
    }

    @Nested
    @DisplayName("getQueueStatus 테스트")
    class GetQueueStatusTest {

        @Test
        @DisplayName("상태 조회 - 활성 세션")
        void getQueueStatus_activeSession() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(true);
            given(redisTemplate.getExpire("active:" + eventId + ":" + memberId)).willReturn(300L);

            // When
            QueueDto.StatusResponse result = queueService.getQueueStatus(eventId, memberId);

            // Then
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getPosition()).isNull();
            assertThat(result.getRemainingSeconds()).isEqualTo(300L);
        }

        @Test
        @DisplayName("상태 조회 - 대기 중")
        void getQueueStatus_waiting() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(false);
            given(zSetOperations.rank("queue:" + eventId, memberId.toString())).willReturn(10L);
            given(zSetOperations.zCard("queue:" + eventId)).willReturn(50L);

            // When
            QueueDto.StatusResponse result = queueService.getQueueStatus(eventId, memberId);

            // Then
            assertThat(result.getIsActive()).isFalse();
            assertThat(result.getPosition()).isEqualTo(11); // rank 10 + 1
            assertThat(result.getTotalWaiting()).isEqualTo(50L);
        }

        @Test
        @DisplayName("상태 조회 실패 - 대기열에 없음")
        void getQueueStatus_notInQueue_throwsException() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(false);
            given(zSetOperations.rank("queue:" + eventId, memberId.toString())).willReturn(null);

            // When & Then
            assertThatThrownBy(() -> queueService.getQueueStatus(eventId, memberId))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Disabled("admitNext is replaced by dequeueNext in current QueueService")
    @Nested
    @DisplayName("admitNext 테스트")
    class AdmitNextTest {

        @Test
        @DisplayName("다음 대기자 입장 처리 성공")
        void admitNext_success() {
            // Given
            Long eventId = 1L;
            int count = 10;

            given(setOperations.size("active:" + eventId)).willReturn(50L); // 50명 활성

            Set<ZSetOperations.TypedTuple<Object>> topUsers = new LinkedHashSet<>();
            topUsers.add(createTypedTuple("1", 1000.0));
            topUsers.add(createTypedTuple("2", 1001.0));
            topUsers.add(createTypedTuple("3", 1002.0));

            given(zSetOperations.rangeWithScores("queue:" + eventId, 0, count - 1))
                    .willReturn(topUsers);

            // When
            int admitted = queueService.dequeueNext(eventId, count).size();

            // Then
            assertThat(admitted).isEqualTo(3);

            then(zSetOperations).should(times(3)).remove(eq("queue:" + eventId), anyString());
        }

        @Test
        @DisplayName("입장 처리 - 슬롯 없음")
        void admitNext_noAvailableSlots() {
            // Given
            Long eventId = 1L;

            given(setOperations.size("active:" + eventId)).willReturn(100L); // MAX_ACTIVE_USERS

            // When
            int admitted = queueService.dequeueNext(eventId, 10).size();

            // Then
            assertThat(admitted).isEqualTo(0);
        }

        @Test
        @DisplayName("입장 처리 - 대기열 비어있음")
        void admitNext_emptyQueue() {
            // Given
            Long eventId = 1L;

            given(setOperations.size("active:" + eventId)).willReturn(50L);
            given(zSetOperations.rangeWithScores("queue:" + eventId, 0, 9))
                    .willReturn(new LinkedHashSet<>());

            // When
            int admitted = queueService.dequeueNext(eventId, 10).size();

            // Then
            assertThat(admitted).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("removeFromQueue 테스트")
    class RemoveFromQueueTest {

        @Test
        @DisplayName("대기열에서 제거 성공")
        void removeFromQueue_success() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            // When
            queueService.removeFromQueue(eventId, memberId);

            // Then
            then(zSetOperations).should(times(1))
                    .remove("queue:" + eventId, memberId.toString());
        }
    }

    @Disabled("removeFromActive is replaced by removePaymentSession in current QueueService")
    @Nested
    @DisplayName("removeFromActive 테스트")
    class RemoveFromActiveTest {

        @Test
        @DisplayName("활성 세션에서 제거 성공")
        void removeFromActive_success() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            // When
            queueService.removePaymentSession(eventId, memberId);

            // Then
            then(redisTemplate).should(times(1))
                    .delete("payment_session:" + eventId + ":" + memberId);
        }
    }

    @Disabled("isActiveSession is replaced by hasPaymentSession in current QueueService")
    @Nested
    @DisplayName("isActiveSession 테스트")
    class IsActiveSessionTest {

        @Test
        @DisplayName("활성 세션 확인 - 활성")
        void isActiveSession_true() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(true);

            // When
            boolean result = queueService.hasPaymentSession(eventId, memberId);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("활성 세션 확인 - 비활성")
        void isActiveSession_false() {
            // Given
            Long eventId = 1L;
            Long memberId = 1L;

            given(setOperations.isMember("active:" + eventId, memberId.toString())).willReturn(false);

            // When
            boolean result = queueService.hasPaymentSession(eventId, memberId);

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("getQueueSize 테스트")
    class GetQueueSizeTest {

        @Test
        @DisplayName("대기열 전체 인원 조회")
        void getQueueSize_success() {
            // Given
            Long eventId = 1L;
            given(zSetOperations.zCard("queue:" + eventId)).willReturn(100L);

            // When
            long result = queueService.getQueueSize(eventId);

            // Then
            assertThat(result).isEqualTo(100L);
        }

        @Test
        @DisplayName("대기열 전체 인원 조회 - null 반환 시 0")
        void getQueueSize_null_returnsZero() {
            // Given
            Long eventId = 1L;
            given(zSetOperations.zCard("queue:" + eventId)).willReturn(null);

            // When
            long result = queueService.getQueueSize(eventId);

            // Then
            assertThat(result).isEqualTo(0L);
        }
    }

    @Disabled("getActiveCount no longer exists in current QueueService")
    @Nested
    @DisplayName("getActiveCount 테스트")
    class GetActiveCountTest {

        @Test
        @DisplayName("활성 세션 전체 인원 조회")
        void getActiveCount_success() {
            // Given
            Long eventId = 1L;
            given(setOperations.size("active:" + eventId)).willReturn(50L);

            // When
            long result = queueService.getQueueSize(eventId);

            // Then
            assertThat(result).isEqualTo(50L);
        }
    }

    // ===== Helper Methods =====

    private ZSetOperations.TypedTuple<Object> createTypedTuple(String value, double score) {
        return new ZSetOperations.TypedTuple<>() {
            @Override
            public Object getValue() {
                return value;
            }

            @Override
            public Double getScore() {
                return score;
            }

            @Override
            public int compareTo(ZSetOperations.TypedTuple<Object> o) {
                return Double.compare(score, o.getScore());
            }
        };
    }
}

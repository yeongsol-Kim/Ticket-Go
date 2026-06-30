package com.yeongsol.ticketgo.domain.queue.service;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;
import com.yeongsol.ticketgo.domain.queue.dto.QueueDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 대기열 서비스
 * - 대기열 진입 시 ticketCount 함께 저장
 * - 스케줄러가 dequeueNext() 호출 → BookingService로 예매 생성 → issuePaymentSession()으로 bookingId 저장
 * - 클라이언트 폴링 시 bookingId 반환 → 결제 페이지로 직행
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String QUEUE_KEY_PREFIX = "queue:";
    private static final String QUEUE_TICKET_COUNT_KEY_PREFIX = "queue_ticket_count:";
    private static final String PAYMENT_SESSION_KEY_PREFIX = "payment_session:";

    public static final long PAYMENT_SESSION_DURATION_SECONDS = 600;

    // 스케줄러에서 dequeueNext() 결과로 받는 사용자 정보
    public record QueuedUser(Long memberId, int ticketCount) {}

    /**
     * 대기열 진입 - ticketCount 함께 저장
     */
    public QueueDto.EnterResponse enterQueue(Long eventId, Long memberId, int ticketCount) {
        String queueKey = getQueueKey(eventId);

        // =======================
        // 1. 중복 진입 방지
        // =======================

        // 이미 결제 세션이 있으면 재진입 차단
        if (hasPaymentSession(eventId, memberId)) {
            throw new BusinessException(ErrorCode.PAYMENT_SESSION_ALREADY_EXISTS);
        }

        // 이미 대기열에 있으면 현재 순번 반환
        Double existingScore = redisTemplate.opsForZSet().score(queueKey, memberId.toString());
        if (existingScore != null) {
            Long rank = redisTemplate.opsForZSet().rank(queueKey, memberId.toString());
            return QueueDto.EnterResponse.builder()
                    .eventId(eventId)
                    .memberId(memberId)
                    .position(rank != null ? rank.intValue() + 1 : 0)
                    .timestamp(existingScore.longValue())
                    .build();
        }


        // =======================
        // 2. 대기열 진입
        // =======================

        // 대기열 진입 - timestamp를 score로 저장해 진입 순서 보장
        long timestamp = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(queueKey, memberId.toString(), timestamp);

        // ticketCount 별도 저장
        redisTemplate.opsForValue().set(getTicketCountKey(eventId, memberId), String.valueOf(ticketCount));

        Long rank = redisTemplate.opsForZSet().rank(queueKey, memberId.toString());
        int position = rank != null ? rank.intValue() + 1 : 0;

        log.info("대기열 진입: eventId={}, memberId={}, ticketCount={}, position={}",
                eventId, memberId, ticketCount, position);

        return QueueDto.EnterResponse.builder()
                .eventId(eventId)
                .memberId(memberId)
                .position(position)
                .timestamp(timestamp)
                .build();
    }

    /**
     * 대기열 상태 조회
     */
    public QueueDto.StatusResponse getQueueStatus(Long eventId, Long memberId) {
        String queueKey = getQueueKey(eventId);

        // 결제 세션 보유 시 bookingId와 남은 시간 반환
        if (hasPaymentSession(eventId, memberId)) {
            Long ttl = redisTemplate.getExpire(getPaymentSessionKey(eventId, memberId));
            Long bookingId = getPaymentSessionBookingId(eventId, memberId);
            return QueueDto.StatusResponse.builder()
                    .eventId(eventId)
                    .memberId(memberId)
                    .isActive(true)
                    .remainingSeconds(ttl != null ? ttl : 0L)
                    .bookingId(bookingId)
                    .build();
        }

        // 대기열 순번 확인
        Long rank = redisTemplate.opsForZSet().rank(queueKey, memberId.toString());
        if (rank == null) {
            throw new BusinessException(ErrorCode.NOT_IN_QUEUE);
        }

        Long totalWaiting = redisTemplate.opsForZSet().zCard(queueKey);

        return QueueDto.StatusResponse.builder()
                .eventId(eventId)
                .memberId(memberId)
                .position(rank.intValue() + 1)
                .totalWaiting(totalWaiting != null ? totalWaiting : 0L)
                .isActive(false)
                .remainingSeconds(null)
                .bookingId(null)
                .build();
    }

    /**
     * 대기열 상위 N명 퇴장 - 스케줄러에서 호출
     * 순서 정보와 ticketCount를 함께 반환하고 대기열에서 제거
     */
    public List<QueuedUser> dequeueNext(Long eventId, int count) {
        String queueKey = getQueueKey(eventId);

        Set<ZSetOperations.TypedTuple<Object>> topUsers = redisTemplate.opsForZSet().rangeWithScores(queueKey, 0, count - 1);

        if (topUsers == null || topUsers.isEmpty()) { return List.of(); }

        List<QueuedUser> result = new ArrayList<>();
        for (ZSetOperations.TypedTuple<Object> user : topUsers) {
            String memberIdStr = (String) user.getValue();
            if (memberIdStr == null) continue;

            Long memberId = Long.parseLong(memberIdStr);

            // 대기열에서 제거
            redisTemplate.opsForZSet().remove(queueKey, memberIdStr);

            // ticketCount 조회 후 키 제거
            Object countValue = redisTemplate.opsForValue().get(getTicketCountKey(eventId, memberId));
            int ticketCount = countValue != null ? Integer.parseInt(countValue.toString()) : 1;
            redisTemplate.delete(getTicketCountKey(eventId, memberId));

            result.add(new QueuedUser(memberId, ticketCount));
        }

        return result;
    }

    /**
     * 대기열 상위 N명 조회 (제거하지 않음) - 스케줄러에서 호출
     * dequeueNext와 달리 큐에서 제거하지 않는다.
     * 세션 발급에 성공한 뒤에야 removeFromQueue로 제거함으로써,
     * "큐에서 제거됐지만 세션은 아직 없는" 순간(NOT_IN_QUEUE 오거절)을 없앤다.
     */
    public List<QueuedUser> peekNext(Long eventId, int count) {
        String queueKey = getQueueKey(eventId);

        Set<ZSetOperations.TypedTuple<Object>> topUsers = redisTemplate.opsForZSet().rangeWithScores(queueKey, 0, count - 1);

        if (topUsers == null || topUsers.isEmpty()) { return List.of(); }

        List<QueuedUser> result = new ArrayList<>();
        for (ZSetOperations.TypedTuple<Object> user : topUsers) {
            String memberIdStr = (String) user.getValue();
            if (memberIdStr == null) continue;

            Long memberId = Long.parseLong(memberIdStr);
            Object countValue = redisTemplate.opsForValue().get(getTicketCountKey(eventId, memberId));
            int ticketCount = countValue != null ? Integer.parseInt(countValue.toString()) : 1;

            result.add(new QueuedUser(memberId, ticketCount));
        }

        return result;
    }

    /**
     * 결제 세션 발급 - 예매 생성 성공 후 스케줄러에서 호출
     * bookingId를 값으로 저장해 클라이언트가 결제 페이지로 직행 가능
     */
    public void issuePaymentSession(Long eventId, Long memberId, Long bookingId) {
        redisTemplate.opsForValue().set(
                getPaymentSessionKey(eventId, memberId),
                bookingId.toString(),
                Duration.ofSeconds(PAYMENT_SESSION_DURATION_SECONDS)
        );
        log.info("결제 세션 발급: eventId={}, memberId={}, bookingId={}", eventId, memberId, bookingId);
    }

    /**
     * 결제 세션 보유 여부 확인
     */
    public boolean hasPaymentSession(Long eventId, Long memberId) {
        Boolean exists = redisTemplate.hasKey(getPaymentSessionKey(eventId, memberId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 결제 세션에서 bookingId 조회
     */
    public Long getPaymentSessionBookingId(Long eventId, Long memberId) {
        Object value = redisTemplate.opsForValue().get(getPaymentSessionKey(eventId, memberId));
        return value != null ? Long.parseLong(value.toString()) : null;
    }

    /**
     * 결제 세션 남은 시간 조회 (초)
     */
    public long getPaymentSessionRemainingSeconds(Long eventId, Long memberId) {
        Long ttl = redisTemplate.getExpire(getPaymentSessionKey(eventId, memberId));
        return ttl != null && ttl > 0 ? ttl : 0L;
    }

    /**
     * 결제 세션 제거
     */
    public void removePaymentSession(Long eventId, Long memberId) {
        redisTemplate.delete(getPaymentSessionKey(eventId, memberId));
        log.info("결제 세션 제거: eventId={}, memberId={}", eventId, memberId);
    }

    /**
     * 대기열에서 제거 (사용자 직접 이탈)
     */
    public void removeFromQueue(Long eventId, Long memberId) {
        redisTemplate.opsForZSet().remove(getQueueKey(eventId), memberId.toString());
        redisTemplate.delete(getTicketCountKey(eventId, memberId));
        log.info("대기열에서 제거: eventId={}, memberId={}", eventId, memberId);
    }

    /**
     * 대기열 전체 인원 조회
     */
    public long getQueueSize(Long eventId) {
        Long size = redisTemplate.opsForZSet().zCard(getQueueKey(eventId));
        return size != null ? size : 0L;
    }

    private String getQueueKey(Long eventId) {
        return QUEUE_KEY_PREFIX + eventId;
    }

    private String getTicketCountKey(Long eventId, Long memberId) {
        return QUEUE_TICKET_COUNT_KEY_PREFIX + eventId + ":" + memberId;
    }

    private String getPaymentSessionKey(Long eventId, Long memberId) {
        return PAYMENT_SESSION_KEY_PREFIX + eventId + ":" + memberId;
    }
}

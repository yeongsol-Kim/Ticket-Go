package com.yeongsol.ticketgo.domain.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class QueueDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EnterRequest {
        private Long eventId;

        @NotNull
        @Min(1)
        @Max(4)
        private Integer ticketCount;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StatusResponse {
        private Long eventId;
        private Long memberId;
        private Integer position;
        private Long totalWaiting;
        private Boolean isActive;
        private Long remainingSeconds;
        private Long bookingId;     // 결제 세션 발급 시 생성된 bookingId
        private Integer nextPollSeconds;  // 다음 폴링까지 권장 대기(초), 대기 중일 때만
        private Boolean streamRecommended; // true면 폴링 대신 /api/queue/stream(SSE)으로 갈아탈 것 (차례가 가까움)
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EnterResponse {
        private Long eventId;
        private Long memberId;
        private Integer position;
        private Long timestamp;
        private Integer nextPollSeconds;  // 첫 폴링까지 권장 대기(초)
        private Boolean streamRecommended; // true면 바로 SSE 구독 (진입 순번이 이미 가까움)
    }

    /**
     * SSE admitted 이벤트 본문. sentAt(서버 전송 시각, epoch ms)으로 클라이언트가 통보 지연을 잰다
     */
    public record AdmittedEvent(Long eventId, Long bookingId, long sentAt) {
    }
}

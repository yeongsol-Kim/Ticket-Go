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
    }
}

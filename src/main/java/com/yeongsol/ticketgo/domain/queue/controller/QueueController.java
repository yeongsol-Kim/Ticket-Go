package com.yeongsol.ticketgo.domain.queue.controller;

import com.yeongsol.ticketgo.config.security.JwtTokenProvider;
import com.yeongsol.ticketgo.domain.queue.dto.QueueDto;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 대기열 컨트롤러
 */
@Tag(name = "Queue", description = "대기열 API")
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 대기열 진입
     */
    @Operation(
            summary = "대기열 진입",
            description = "이벤트 대기열에 진입합니다",
            security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @PostMapping("/enter")
    public ResponseEntity<QueueDto.EnterResponse> enterQueue(
            @RequestBody QueueDto.EnterRequest request,
            HttpServletRequest httpRequest
    ) {
        Long memberId = getMemberIdFromToken(httpRequest);
        QueueDto.EnterResponse response = queueService.enterQueue(request.getEventId(), memberId, request.getTicketCount());
        return ResponseEntity.ok(response);
    }

    /**
     * 대기열 상태 조회
     */
    @Operation(
            summary = "대기열 상태 조회",
            description = "현재 대기열 상태를 조회합니다. isActive=true이면 결제 세션이 발급된 상태입니다",
            security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @GetMapping("/status/{eventId}")
    public ResponseEntity<QueueDto.StatusResponse> getQueueStatus(
            @PathVariable Long eventId,
            HttpServletRequest httpRequest
    ) {
        Long memberId = getMemberIdFromToken(httpRequest);
        QueueDto.StatusResponse response = queueService.getQueueStatus(eventId, memberId);
        return ResponseEntity.ok(response);
    }

    /**
     * 대기열 통계 조회 (관리자용)
     */
    @Operation(
            summary = "대기열 통계 조회",
            description = "이벤트의 대기열 통계를 조회합니다",
            security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @GetMapping("/stats/{eventId}")
    public ResponseEntity<QueueStatsResponse> getQueueStats(@PathVariable Long eventId) {
        long queueSize = queueService.getQueueSize(eventId);

        QueueStatsResponse response = QueueStatsResponse.builder()
                .eventId(eventId)
                .queueSize(queueSize)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 대기열에서 나가기
     */
    @Operation(
            summary = "대기열 나가기",
            description = "대기열에서 나갑니다. 결제 세션이 있는 경우 함께 제거됩니다",
            security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @DeleteMapping("/leave/{eventId}")
    public ResponseEntity<Void> leaveQueue(
            @PathVariable Long eventId,
            HttpServletRequest httpRequest
    ) {
        Long memberId = getMemberIdFromToken(httpRequest);
        queueService.removeFromQueue(eventId, memberId);
        queueService.removePaymentSession(eventId, memberId);
        return ResponseEntity.ok().build();
    }

    /**
     * JWT에서 memberId 추출
     */
    private Long getMemberIdFromToken(HttpServletRequest request) {
        String token = jwtTokenProvider.resolveToken(request);
        return jwtTokenProvider.getMemberId(token);
    }

    /**
     * 대기열 통계 응답
     */
    @lombok.Getter
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    public static class QueueStatsResponse {
        private Long eventId;
        private Long queueSize;
    }
}

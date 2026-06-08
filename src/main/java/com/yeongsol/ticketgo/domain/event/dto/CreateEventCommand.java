package com.yeongsol.ticketgo.domain.event.dto;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 이벤트 생성 커맨드 (Service 계층용)
 * Controller의 Request DTO를 변환하여 Service로 전달
 */
@Builder
public record CreateEventCommand(
        String name,
        String description,
        LocalDateTime startDateTime,
        LocalDateTime saleStartDateTime,
        LocalDateTime saleEndDateTime,
        Integer totalTickets,
        Integer price,
        String venue
) {
}

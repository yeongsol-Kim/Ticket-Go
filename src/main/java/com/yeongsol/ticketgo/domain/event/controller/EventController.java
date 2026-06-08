package com.yeongsol.ticketgo.domain.event.controller;

import com.yeongsol.ticketgo.domain.event.dto.CreateEventCommand;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 이벤트 관리 API
 */
@Tag(name = "Event", description = "이벤트 API")
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "이벤트 생성", description = "새로운 이벤트를 생성합니다 (관리자)",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @PostMapping
    public ResponseEntity<?> createEvent(@Valid @RequestBody CreateEventRequest request) {
        CreateEventCommand command = CreateEventCommand.builder()
                .name(request.name())
                .description(request.description())
                .startDateTime(request.startDateTime())
                .saleStartDateTime(request.saleStartDateTime())
                .saleEndDateTime(request.saleEndDateTime())
                .totalTickets(request.totalTickets())
                .price(request.price())
                .venue(request.venue())
                .build();

        Event event = eventService.createEvent(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EventResponse.from(event));
    }

    @Operation(summary = "판매 중인 이벤트 조회", description = "현재 판매 중인 이벤트 목록을 조회합니다")
    @GetMapping("/on-sale")
    public ResponseEntity<?> getOnSaleEvents() {
        List<Event> events = eventService.getOnSaleEvents();
        List<EventResponse> response = events.stream()
                .map(EventResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }


    @Operation(summary = "판매 예정 이벤트 조회", description = "판매 예정인 이벤트 목록을 조회합니다")
    @GetMapping("/upcoming")
    public ResponseEntity<?> getUpcomingEvents() {
        List<Event> events = eventService.getUpcomingEvents();
        List<EventResponse> response = events.stream()
                .map(EventResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "이벤트 상세 조회", description = "특정 이벤트의 상세 정보를 조회합니다")
    @GetMapping("/{id}")
    public ResponseEntity<?> getEvent(@PathVariable Long id) {
        Event event = eventService.findById(id);
        return ResponseEntity.ok(EventResponse.from(event));
    }

    /**
     * 이벤트 판매 시작 (관리자)
     * POST /api/events/{id}/start-sale
     */
    @PostMapping("/{id}/start-sale")
    public ResponseEntity<?> startSale(@PathVariable Long id) {
        eventService.startSale(id);
        return ResponseEntity.ok().build();
    }

    /**
     * 이벤트 취소 (관리자)
     * POST /api/events/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelEvent(@PathVariable Long id) {
        eventService.cancelEvent(id);
        return ResponseEntity.ok().build();
    }

    /**
     * 재고 확인
     * GET /api/events/{id}/availability
     */
    @GetMapping("/{id}/availability")
    public ResponseEntity<?> checkAvailability(@PathVariable Long id) {
        boolean available = eventService.hasAvailableTickets(id);
        Event event = eventService.findById(id);
        return ResponseEntity.ok(new AvailabilityResponse(
                available,
                event.getAvailableTickets(),
                event.getTotalTickets()
        ));
    }

    // ===== Request/Response DTOs =====

    record CreateEventRequest(
            @NotBlank(message = "이벤트 이름은 필수입니다")
            String name,

            String description,

            @NotNull(message = "공연 시작 시간은 필수입니다")
            LocalDateTime startDateTime,

            @NotNull(message = "판매 시작 시간은 필수입니다")
            LocalDateTime saleStartDateTime,

            @NotNull(message = "판매 종료 시간은 필수입니다")
            LocalDateTime saleEndDateTime,

            @NotNull(message = "총 티켓 수는 필수입니다")
            @Min(value = 1, message = "최소 1장 이상의 티켓이 필요합니다")
            Integer totalTickets,

            @NotNull(message = "가격은 필수입니다")
            @Min(value = 0, message = "가격은 0원 이상이어야 합니다")
            Integer price,

            @NotBlank(message = "공연장 정보는 필수입니다")
            String venue
    ) {}

    record EventResponse(
            Long id,
            String name,
            String description,
            LocalDateTime startDateTime,
            LocalDateTime saleStartDateTime,
            LocalDateTime saleEndDateTime,
            String status,
            Integer totalTickets,
            Integer availableTickets,
            Integer price,
            String venue
    ) {
        static EventResponse from(Event event) {
            return new EventResponse(
                    event.getId(),
                    event.getName(),
                    event.getDescription(),
                    event.getStartDateTime(),
                    event.getSaleStartDateTime(),
                    event.getSaleEndDateTime(),
                    event.getStatus().name(),
                    event.getTotalTickets(),
                    event.getAvailableTickets(),
                    event.getPrice(),
                    event.getVenue()
            );
        }
    }

    record AvailabilityResponse(
            boolean available,
            Integer availableTickets,
            Integer totalTickets
    ) {}
}

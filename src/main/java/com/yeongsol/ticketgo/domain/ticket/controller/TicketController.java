package com.yeongsol.ticketgo.domain.ticket.controller;

import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 티켓 관리 API
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    /**
     * 내 티켓 목록 조회
     * GET /api/tickets/me
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyTickets() {
        // TODO: 인증된 사용자 ID 가져오기
        Long memberId = 1L; // 임시

        List<Ticket> tickets = ticketService.getMyTickets(memberId);
        List<TicketResponse> response = tickets.stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * 예약별 티켓 조회
     * GET /api/tickets/booking/{bookingId}
     */
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<?> getTicketsByBooking(@PathVariable Long bookingId) {
        // TODO: 본인 예약인지 확인

        List<Ticket> tickets = ticketService.getTicketsByBooking(bookingId);
        List<TicketResponse> response = tickets.stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * 티켓 상세 조회 (QR 코드)
     * GET /api/tickets/{ticketNumber}
     */
    @GetMapping("/{ticketNumber}")
    public ResponseEntity<?> getTicket(@PathVariable String ticketNumber) {
        Ticket ticket = ticketService.findByTicketNumber(ticketNumber);
        return ResponseEntity.ok(TicketResponse.from(ticket));
    }

    /**
     * 티켓 검증 (입장 시 사용)
     * POST /api/tickets/validate
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateTicket(@RequestBody ValidateRequest request) {
        boolean valid = ticketService.validateTicket(
                request.ticketNumber(),
                request.eventId()
        );

        if (valid) {
            return ResponseEntity.ok(new ValidateResponse(true, "유효한 티켓입니다"));
        } else {
            return ResponseEntity.ok(new ValidateResponse(false, "유효하지 않은 티켓입니다"));
        }
    }

    /**
     * 이벤트별 티켓 통계 (관리자)
     * GET /api/tickets/stats/event/{eventId}
     */
    @GetMapping("/stats/event/{eventId}")
    public ResponseEntity<?> getTicketStats(@PathVariable Long eventId) {
        long bookedCount = ticketService.getBookedTicketCount(eventId);
        long cancelledCount = ticketService.getCancelledTicketCount(eventId);

        return ResponseEntity.ok(new TicketStatsResponse(
                bookedCount,
                cancelledCount,
                bookedCount + cancelledCount
        ));
    }

    // ===== Request/Response DTOs =====

    record TicketResponse(
            Long id,
            Long eventId,
            Long bookingId,
            String ticketNumber,
            String status,
            LocalDateTime createdAt
    ) {
        static TicketResponse from(Ticket ticket) {
            return new TicketResponse(
                    ticket.getId(),
                    ticket.getEventId(),
                    ticket.getBookingId(),
                    ticket.getTicketNumber(),
                    ticket.getStatus().name(),
                    ticket.getCreatedAt()
            );
        }
    }

    record ValidateRequest(
            String ticketNumber,
            Long eventId
    ) {}

    record ValidateResponse(
            boolean valid,
            String message
    ) {}

    record TicketStatsResponse(
            long bookedCount,
            long cancelledCount,
            long totalCount
    ) {}
}

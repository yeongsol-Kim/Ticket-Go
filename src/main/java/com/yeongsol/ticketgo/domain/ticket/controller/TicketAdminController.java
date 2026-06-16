package com.yeongsol.ticketgo.domain.ticket.controller;

import com.yeongsol.ticketgo.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/tickets")
@RequiredArgsConstructor
public class TicketAdminController {

    private final TicketService ticketService;

    /**
     * 이벤트 티켓 사전 발급 (Pre-issued 방식 테스트용)
     * POST /api/admin/tickets/pre-issue?eventId=1&count=1000
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/pre-issue")
    public ResponseEntity<?> preIssueTickets(
            @RequestParam Long eventId,
            @RequestParam int count) {
        ticketService.preIssueTickets(eventId, count);
        return ResponseEntity.ok("티켓 " + count + "장 사전 발급 완료 (eventId=" + eventId + ")");
    }
}

package com.yeongsol.ticketgo.domain.ticket.model;

public enum TicketStatus {
    AVAILABLE,  // 사전 발급됨 (미할당)
    BOOKED,     // 발급됨
    CANCELLED   // 취소됨
}

package com.yeongsol.ticketgo.domain.booking.model;

public enum BookingStatus {
    RESERVED,   // 티켓 예약됨 (결제 대기)
    CONFIRMED,  // 예약 확정 (결제 완료)
    CANCELLED,  // 취소
    EXPIRED     // 시간 만료
}

package com.yeongsol.ticketgo.domain.payment.model;

public enum PaymentStatus {
    PENDING,   // 대기중
    APPROVED,  // 승인됨
    FAILED,    // 실패
    CANCELLED, // 취소
    REFUNDED   // 환불
}

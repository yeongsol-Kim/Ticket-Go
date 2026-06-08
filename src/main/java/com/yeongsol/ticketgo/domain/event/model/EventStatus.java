package com.yeongsol.ticketgo.domain.event.model;

public enum EventStatus {
    DRAFT,      // 작성중
    ON_SALE,    // 판매중
    SOLD_OUT,   // 매진
    CANCELLED,  // 취소
    COMPLETED   // 종료
}

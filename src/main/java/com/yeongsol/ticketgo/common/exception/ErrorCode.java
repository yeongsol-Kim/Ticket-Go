package com.yeongsol.ticketgo.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드 정의
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "지원하지 않는 HTTP 메서드입니다"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C003", "서버 오류가 발생했습니다"),
    INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "C004", "잘못된 타입입니다"),

    // Member
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "M001", "존재하지 않는 회원입니다"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "M002", "이미 존재하는 이메일입니다"),
    MEMBER_DISABLED(HttpStatus.FORBIDDEN, "M003", "비활성화된 회원입니다"),

    // Event
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "E001", "존재하지 않는 이벤트입니다"),
    EVENT_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "E002", "판매 중인 이벤트가 아닙니다"),
    EVENT_SOLD_OUT(HttpStatus.CONFLICT, "E003", "매진된 이벤트입니다"),
    NOT_ENOUGH_TICKETS(HttpStatus.CONFLICT, "E004", "티켓 재고가 부족합니다"),

    // Booking
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "B001", "존재하지 않는 예약입니다"),
    BOOKING_ALREADY_EXISTS(HttpStatus.CONFLICT, "B002", "이미 해당 이벤트의 예약이 존재합니다"),
    BOOKING_EXPIRED(HttpStatus.BAD_REQUEST, "B003", "예약이 만료되었습니다"),
    BOOKING_ALREADY_CONFIRMED(HttpStatus.BAD_REQUEST, "B004", "이미 확정된 예약입니다"),
    BOOKING_CANNOT_CANCEL(HttpStatus.BAD_REQUEST, "B005", "취소할 수 없는 예약입니다"),
    OPTIMISTIC_LOCK_FAILURE(HttpStatus.CONFLICT, "B006", "일시적인 오류가 발생했습니다. 다시 시도해주세요"),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "존재하지 않는 결제입니다"),
    PAYMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "P002", "이미 결제가 진행 중이거나 완료되었습니다"),
    PAYMENT_NOT_PENDING(HttpStatus.BAD_REQUEST, "P003", "결제 대기 상태가 아닙니다"),
    PAYMENT_NOT_APPROVED(HttpStatus.BAD_REQUEST, "P004", "승인된 결제가 아닙니다"),

    // Ticket
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "T001", "존재하지 않는 티켓입니다"),
    TICKET_ALREADY_CANCELLED(HttpStatus.BAD_REQUEST, "T002", "이미 취소된 티켓입니다"),
    TICKET_INVALID(HttpStatus.BAD_REQUEST, "T003", "유효하지 않은 티켓입니다"),
    TICKET_EVENT_MISMATCH(HttpStatus.BAD_REQUEST, "T004", "해당 이벤트의 티켓이 아닙니다"),

    // Queue
    NOT_IN_QUEUE(HttpStatus.NOT_FOUND, "Q001", "대기열에 없습니다"),
    PAYMENT_SESSION_ALREADY_EXISTS(HttpStatus.CONFLICT, "Q002", "이미 결제 세션이 존재합니다. 예매를 진행해주세요"),
    PAYMENT_SESSION_REQUIRED(HttpStatus.FORBIDDEN, "Q003", "결제 세션이 없습니다. 대기열을 통해 입장해주세요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}

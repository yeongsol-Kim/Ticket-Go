package com.yeongsol.ticketgo.domain.booking.exception;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;

public class BookingExpiredException extends BusinessException {
    public BookingExpiredException() {
        super(ErrorCode.BOOKING_EXPIRED);
    }
}

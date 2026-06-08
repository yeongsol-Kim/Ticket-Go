package com.yeongsol.ticketgo.domain.booking.exception;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;

public class BookingNotFoundException extends BusinessException {
    public BookingNotFoundException() {
        super(ErrorCode.BOOKING_NOT_FOUND);
    }
}

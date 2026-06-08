package com.yeongsol.ticketgo.domain.payment.exception;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;

public class PaymentAlreadyExistsException extends BusinessException {
    public PaymentAlreadyExistsException() {
        super(ErrorCode.PAYMENT_ALREADY_EXISTS);
    }
}

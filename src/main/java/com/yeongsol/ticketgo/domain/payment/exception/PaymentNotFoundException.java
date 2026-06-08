package com.yeongsol.ticketgo.domain.payment.exception;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;

public class PaymentNotFoundException extends BusinessException {
    public PaymentNotFoundException() {
        super(ErrorCode.PAYMENT_NOT_FOUND);
    }
}

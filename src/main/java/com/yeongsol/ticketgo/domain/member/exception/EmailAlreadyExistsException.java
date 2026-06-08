package com.yeongsol.ticketgo.domain.member.exception;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;

public class EmailAlreadyExistsException extends BusinessException {
    public EmailAlreadyExistsException() {
        super(ErrorCode.EMAIL_ALREADY_EXISTS);
    }
}

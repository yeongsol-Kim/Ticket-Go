package com.yeongsol.ticketgo.domain.event.exception;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;

public class EventNotFoundException extends BusinessException {
    public EventNotFoundException() {
        super(ErrorCode.EVENT_NOT_FOUND);
    }
}

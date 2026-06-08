package com.yeongsol.ticketgo.domain.ticket.exception;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;

public class TicketNotFoundException extends BusinessException {
    public TicketNotFoundException() {
        super(ErrorCode.TICKET_NOT_FOUND);
    }
}

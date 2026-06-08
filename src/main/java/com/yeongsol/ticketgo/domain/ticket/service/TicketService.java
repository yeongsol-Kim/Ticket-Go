package com.yeongsol.ticketgo.domain.ticket.service;

import com.yeongsol.ticketgo.domain.ticket.exception.TicketNotFoundException;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.model.TicketStatus;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;

    /**
     * 티켓 번호로 조회
     * QR 코드 스캔 시 사용
     */
    public Ticket findByTicketNumber(String ticketNumber) {
        return ticketRepository.findByTicketNumber(ticketNumber)
                .orElseThrow(TicketNotFoundException::new);
    }

    /**
     * 예약별 티켓 조회
     */
    public List<Ticket> getTicketsByBooking(Long bookingId) {
        return ticketRepository.findByBookingId(bookingId);
    }

    /**
     * 회원의 티켓 목록 조회
     */
    public List<Ticket> getMyTickets(Long memberId) {
        return ticketRepository.findByMemberId(memberId);
    }

    /**
     * 회원의 특정 이벤트 티켓 조회
     */
    public List<Ticket> getMyTicketsForEvent(Long memberId, Long eventId) {
        return ticketRepository.findByMemberIdAndEventId(memberId, eventId);
    }

    /**
     * 티켓 검증 (입장 시)
     * 1. 티켓 존재 확인
     * 2. 취소되지 않았는지 확인
     * 3. 이벤트 일치 확인
     */
    public boolean validateTicket(String ticketNumber, Long eventId) {
        Ticket ticket = findByTicketNumber(ticketNumber);

        // 취소된 티켓
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            log.warn("취소된 티켓 사용 시도 - ticketNumber: {}", ticketNumber);
            return false;
        }

        // 이벤트 불일치
        if (!ticket.getEventId().equals(eventId)) {
            log.warn("다른 이벤트 티켓 사용 시도 - ticketNumber: {}, eventId: {}",
                    ticketNumber, eventId);
            return false;
        }

        log.info("티켓 검증 성공 - ticketNumber: {}, eventId: {}", ticketNumber, eventId);
        return true;
    }

    /**
     * 이벤트별 발급된 티켓 수 조회
     */
    public long getBookedTicketCount(Long eventId) {
        return ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.BOOKED);
    }

    /**
     * 이벤트별 취소된 티켓 수 조회
     */
    public long getCancelledTicketCount(Long eventId) {
        return ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.CANCELLED);
    }

    /**
     * 티켓 ID로 조회
     */
    public Ticket findById(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(TicketNotFoundException::new);
    }
}

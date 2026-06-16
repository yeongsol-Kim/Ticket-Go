package com.yeongsol.ticketgo.domain.ticket.repository;

import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.model.TicketStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * 예약별 티켓 목록 조회
     * 예약 상세 조회, 취소 시 사용
     */
    List<Ticket> findByBookingId(Long bookingId);

    /**
     * 이벤트의 AVAILABLE 티켓 1개 조회 (비관적 락)
     * Pre-issued 방식에서 결제 승인 시 사용
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Ticket> findFirstByEventIdAndStatus(Long eventId, TicketStatus status);

    /**
     * 티켓 번호로 조회
     * QR 코드 스캔, 티켓 검증 시 사용
     */
    Optional<Ticket> findByTicketNumber(String ticketNumber);

    /**
     * 이벤트별 발급된 티켓 수 조회
     * 통계, 대시보드용
     */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.eventId = :eventId AND t.status = :status")
    long countByEventIdAndStatus(@Param("eventId") Long eventId,
                                  @Param("status") TicketStatus status);

    /**
     * 회원의 이벤트별 티켓 조회
     * "내 티켓" 목록 조회
     */
    @Query("SELECT t FROM Ticket t " +
           "JOIN Booking b ON t.bookingId = b.id " +
           "WHERE b.memberId = :memberId AND t.eventId = :eventId " +
           "ORDER BY t.createdAt DESC")
    List<Ticket> findByMemberIdAndEventId(@Param("memberId") Long memberId,
                                           @Param("eventId") Long eventId);

    /**
     * 회원의 전체 티켓 조회 (페이징 없이)
     * "내 티켓" 전체 목록
     */
    @Query("SELECT t FROM Ticket t " +
           "JOIN Booking b ON t.bookingId = b.id " +
           "WHERE b.memberId = :memberId " +
           "ORDER BY t.createdAt DESC")
    List<Ticket> findByMemberId(@Param("memberId") Long memberId);

    /**
     * 이벤트별 티켓 상태 통계
     * 관리자 대시보드용
     */
    @Query("SELECT t.status, COUNT(t) FROM Ticket t " +
           "WHERE t.eventId = :eventId " +
           "GROUP BY t.status")
    List<Object[]> countTicketsByStatusForEvent(@Param("eventId") Long eventId);
}

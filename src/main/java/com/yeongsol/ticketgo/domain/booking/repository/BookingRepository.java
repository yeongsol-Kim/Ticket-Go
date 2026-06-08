package com.yeongsol.ticketgo.domain.booking.repository;

import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.model.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * 예약 번호로 조회
     * 예약 상세, 결제 페이지에서 사용
     */
    Optional<Booking> findByBookingNumber(String bookingNumber);

    /**
     * 회원의 예약 목록 조회
     * "내 예약" 페이지
     */
    List<Booking> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    /**
     * 회원의 특정 상태 예약 조회
     * 예: 결제 대기 중인 예약만
     */
    List<Booking> findByMemberIdAndStatusOrderByCreatedAtDesc(Long memberId, BookingStatus status);

    /**
     * 만료된 예약 조회 (배치 처리용) - 핵심 쿼리!
     * 결제 대기 중(RESERVED)이면서 만료 시간이 지난 예약
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.status = :status " +
           "AND b.expiresAt < :now")
    List<Booking> findExpiredBookings(@Param("status") BookingStatus status,
                                       @Param("now") LocalDateTime now);

    /**
     * 특정 상태들의 예약 중 만료 시간이 지난 것 조회
     * 여러 상태를 한 번에 처리할 때
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.status IN :statuses " +
           "AND b.expiresAt < :now")
    List<Booking> findExpiredBookingsByStatuses(@Param("statuses") List<BookingStatus> statuses,
                                                 @Param("now") LocalDateTime now);

    /**
     * 이벤트별 예약 통계
     * 관리자 대시보드용
     */
    @Query("SELECT b.status, COUNT(b), SUM(b.ticketCount) " +
           "FROM Booking b " +
           "WHERE b.eventId = :eventId " +
           "GROUP BY b.status")
    List<Object[]> getBookingStatsByEvent(@Param("eventId") Long eventId);

    /**
     * 회원의 특정 이벤트 예약 존재 여부
     * 중복 예약 방지용
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END " +
           "FROM Booking b " +
           "WHERE b.memberId = :memberId " +
           "AND b.eventId = :eventId " +
           "AND b.status IN :statuses")
    boolean existsByMemberIdAndEventIdAndStatusIn(@Param("memberId") Long memberId,
                                                    @Param("eventId") Long eventId,
                                                    @Param("statuses") List<BookingStatus> statuses);

    /**
     * 이벤트별 확정된 예약 수 조회
     * 실제 판매된 티켓 수 확인
     */
    @Query("SELECT COUNT(b) FROM Booking b " +
           "WHERE b.eventId = :eventId AND b.status = :status")
    long countByEventIdAndStatus(@Param("eventId") Long eventId,
                                  @Param("status") BookingStatus status);
}

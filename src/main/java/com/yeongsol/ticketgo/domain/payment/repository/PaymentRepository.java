package com.yeongsol.ticketgo.domain.payment.repository;

import com.yeongsol.ticketgo.domain.payment.model.Payment;
import com.yeongsol.ticketgo.domain.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 예약별 결제 조회
     * Booking과 1:1 관계
     */
    Optional<Payment> findByBookingId(Long bookingId);

    /**
     * 결제 키로 조회
     * 외부 PG 웹훅 처리 시 사용
     */
    Optional<Payment> findByPaymentKey(String paymentKey);

    /**
     * 회원의 결제 내역 조회
     * "결제 내역" 페이지
     */
    List<Payment> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    /**
     * 회원의 특정 상태 결제 조회
     * 예: 승인된 결제만, 실패한 결제만
     */
    List<Payment> findByMemberIdAndStatusOrderByCreatedAtDesc(Long memberId, PaymentStatus status);

    /**
     * 특정 기간 동안의 결제 조회
     * 정산, 통계용
     */
    @Query("SELECT p FROM Payment p " +
           "WHERE p.status = :status " +
           "AND p.completedAt BETWEEN :startDate AND :endDate " +
           "ORDER BY p.completedAt DESC")
    List<Payment> findByStatusAndCompletedAtBetween(@Param("status") PaymentStatus status,
                                                      @Param("startDate") LocalDateTime startDate,
                                                      @Param("endDate") LocalDateTime endDate);

    /**
     * 결제 상태별 통계
     * 관리자 대시보드용
     */
    @Query("SELECT p.status, COUNT(p), SUM(p.amount) " +
           "FROM Payment p " +
           "GROUP BY p.status")
    List<Object[]> getPaymentStats();

    /**
     * 특정 기간 승인된 결제 총액
     * 매출 통계용
     */
    @Query("SELECT SUM(p.amount) FROM Payment p " +
           "WHERE p.status = :status " +
           "AND p.completedAt BETWEEN :startDate AND :endDate")
    Long getTotalAmountByStatusAndPeriod(@Param("status") PaymentStatus status,
                                          @Param("startDate") LocalDateTime startDate,
                                          @Param("endDate") LocalDateTime endDate);

    /**
     * 대기 중인 결제 조회 (오래된 것부터)
     * 결제 타임아웃 모니터링용
     */
    @Query("SELECT p FROM Payment p " +
           "WHERE p.status = :status " +
           "AND p.requestedAt < :threshold " +
           "ORDER BY p.requestedAt ASC")
    List<Payment> findPendingPaymentsBefore(@Param("status") PaymentStatus status,
                                             @Param("threshold") LocalDateTime threshold);

    /**
     * 예약별 결제 존재 여부
     * 중복 결제 방지용
     */
    boolean existsByBookingId(Long bookingId);
}

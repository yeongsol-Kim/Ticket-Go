package com.yeongsol.ticketgo.domain.event.repository;

import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * 비관적 락으로 이벤트 조회
     * 높은 동시성 상황에서 낙관적 락 충돌이 빈번할 때 사용
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdWithPessimisticLock(@Param("id") Long id);

    /**
     * 판매 중인 이벤트 목록 조회
     * 현재 시간 기준으로 판매 기간 내의 이벤트
     */
    @Query("SELECT e FROM Event e WHERE e.status = :status " +
           "AND e.saleStartDateTime <= :now " +
           "AND e.saleEndDateTime >= :now " +
           "ORDER BY e.startDateTime ASC")
    List<Event> findOnSaleEvents(@Param("status") EventStatus status,
                                  @Param("now") LocalDateTime now);

    /**
     * 판매 예정 이벤트 조회
     * 판매 시작 전이지만 곧 판매될 이벤트
     */
    @Query("SELECT e FROM Event e WHERE e.status = :status " +
           "AND e.saleStartDateTime > :now " +
           "ORDER BY e.saleStartDateTime ASC")
    List<Event> findUpcomingEvents(@Param("status") EventStatus status,
                                    @Param("now") LocalDateTime now);

    /**
     * 종료된 이벤트 조회 (배치 처리용)
     * 공연이 끝났지만 상태가 COMPLETED가 아닌 이벤트
     */
    @Query("SELECT e FROM Event e WHERE e.startDateTime < :now " +
           "AND e.status NOT IN :excludeStatuses")
    List<Event> findEventsToComplete(@Param("now") LocalDateTime now,
                                      @Param("excludeStatuses") List<EventStatus> excludeStatuses);

    /**
     * 재고가 있는 이벤트인지 확인
     */
    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END " +
           "FROM Event e WHERE e.id = :id AND e.availableTickets > 0")
    boolean hasAvailableTickets(@Param("id") Long id);

    /**
     * 상태별 이벤트 조회
     */
    List<Event> findByStatus(EventStatus status);
}

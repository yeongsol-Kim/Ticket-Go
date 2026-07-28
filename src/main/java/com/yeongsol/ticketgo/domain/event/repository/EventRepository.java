package com.yeongsol.ticketgo.domain.event.repository;

import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 재고 원자적 차감 (벌크 B단계) - 조건부 UPDATE 한 방
     *
     * WHERE available_tickets >= :n 이 실행 시점 최신 재고로 재검증 →
     * 오버부킹을 DB가 원자적으로 막는다(@Version 불필요). 영향 행 수 반환:
     *   1 = 차감 성공, 0 = 그 사이 재고가 변해(복원 경로 등) 조건 미충족 → 호출부 재시도.
     *
     * flushAutomatically=true: 호출부의 미flush 엔티티 변경을 벌크 UPDATE 전에 flush.
     * clearAutomatically=false(기본): PC를 비우지 '않는다'. 비우면 같은 트랜잭션의
     *   미flush 엔티티 변경(예: booking.expire())이 폐기되어 소실됨(무한 복원 버그 원인).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Event e SET e.availableTickets = e.availableTickets - :n " +
           "WHERE e.id = :id AND e.availableTickets >= :n")
    int decreaseStock(@Param("id") Long id, @Param("n") int n);

    /**
     * 재고 원자적 복원 (취소/만료) - 무조건 증가 UPDATE
     *
     * 증가는 조건이 필요 없다(항상 안전). 차감(decreaseStock)과 복원이 모두 원자적 UPDATE라
     * 같은 행 락으로 직렬화되어 lost update가 원천적으로 불가능하다(@Version 불필요).
     * 이로써 차감(입장 배치) ↔ 복원(취소/만료)의 동시성이 DB 레벨에서 완결된다.
     *
     * ⚠️ clearAutomatically 금지: 복원 경로는 booking.expire()/cancel() 같은 미flush 엔티티
     *   변경을 함께 커밋해야 하는데, PC를 비우면 그 변경이 폐기되어 booking이 RESERVED로 남고
     *   매 배치마다 재복원되어 재고가 무한 증가한다. flushAutomatically로 순서만 보장한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Event e SET e.availableTickets = e.availableTickets + :n WHERE e.id = :id")
    int increaseStock(@Param("id") Long id, @Param("n") int n);
}

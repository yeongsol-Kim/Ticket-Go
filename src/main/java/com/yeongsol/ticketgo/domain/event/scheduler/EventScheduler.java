package com.yeongsol.ticketgo.domain.event.scheduler;

import com.yeongsol.ticketgo.domain.event.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이벤트 관련 배치 스케줄러
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventScheduler {

    private final EventService eventService;

    /**
     * 종료된 이벤트 자동 완료 처리
     * 매 1시간마다 실행
     *
     * - 공연 시작 시간이 지난 이벤트 찾기
     * - COMPLETED 상태로 변경
     */
    @Scheduled(fixedDelay = 3600000)  // 1시간 = 3,600,000ms
    public void completeExpiredEvents() {
        log.info("종료된 이벤트 완료 처리 배치 시작");

        try {
            eventService.completeExpiredEvents();
            log.info("종료된 이벤트 완료 처리 배치 완료");

        } catch (Exception e) {
            log.error("종료된 이벤트 완료 처리 배치 실패", e);
        }
    }

    /**
     * 매일 새벽 2시에 실행하는 이벤트 정리 작업 (선택사항)
     * 판매 기간 체크, 통계 등
     */
    @Scheduled(cron = "0 0 2 * * *")  // 매일 02:00:00
    public void dailyEventMaintenance() {
        log.info("일일 이벤트 유지보수 작업 시작");

        try {
            // TODO: 판매 시작/종료 시간 체크, 상태 자동 변경 등
            log.info("일일 이벤트 유지보수 작업 완료");

        } catch (Exception e) {
            log.error("일일 이벤트 유지보수 작업 실패", e);
        }
    }
}

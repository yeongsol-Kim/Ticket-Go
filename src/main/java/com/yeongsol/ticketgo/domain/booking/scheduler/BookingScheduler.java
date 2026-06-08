package com.yeongsol.ticketgo.domain.booking.scheduler;

import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약 관련 배치 스케줄러
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingScheduler {

    private final BookingService bookingService;

    /**
     * 만료된 예약 자동 처리
     * 매 1분마다 실행
     *
     * - RESERVED 상태에서 10분 초과한 예약 찾기
     * - EXPIRED로 상태 변경
     * - 티켓 취소
     * - Event 재고 복구
     */
    @Scheduled(fixedDelay = 10000)  // 10초 = 10,000ms
    public void processExpiredBookings() {
        log.info("만료된 예약 처리 배치 시작");

        try {
            bookingService.expireBookings();
            log.info("만료된 예약 처리 배치 완료");

        } catch (Exception e) {
            log.error("만료된 예약 처리 배치 실패", e);
        }
    }

    /**
     * 매일 자정에 실행하는 예약 정리 작업 (선택사항)
     * 오래된 취소/만료 예약 통계 등
     */
    @Scheduled(cron = "0 0 0 * * *")  // 매일 00:00:00
    public void dailyBookingCleanup() {
        log.info("일일 예약 정리 작업 시작");

        try {
            // TODO: 오래된 예약 아카이빙, 통계 등
            log.info("일일 예약 정리 작업 완료");

        } catch (Exception e) {
            log.error("일일 예약 정리 작업 실패", e);
        }
    }
}

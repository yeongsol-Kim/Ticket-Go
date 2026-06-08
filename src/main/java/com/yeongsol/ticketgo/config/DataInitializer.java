package com.yeongsol.ticketgo.config;

import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.model.MemberRole;
import com.yeongsol.ticketgo.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 개발 환경용 초기 데이터 생성
 * dev 프로필에서만 동작
 */
@Slf4j
@Component
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final EventRepository eventRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        initAdmin();
        initTestUser();
        initEvents();
    }

    private void initAdmin() {
        if (memberRepository.existsByEmail("admin@ticketgo.com")) {
            log.info("Admin already exists, skipping...");
            return;
        }

        Member admin = Member.builder()
                .email("admin@ticketgo.com")
                .password(passwordEncoder.encode("admin123"))
                .name("관리자")
                .phoneNumber("010-0000-0000")
                .role(MemberRole.ADMIN)
                .build();

        memberRepository.save(admin);
        log.info("Admin created: admin@ticketgo.com / admin123");
    }

    private void initTestUser() {
        if (memberRepository.existsByEmail("user@test.com")) {
            log.info("Test user already exists, skipping...");
            return;
        }

        Member user = Member.builder()
                .email("user@test.com")
                .password(passwordEncoder.encode("user123"))
                .name("테스트유저")
                .phoneNumber("010-1234-5678")
                .role(MemberRole.USER)
                .build();

        memberRepository.save(user);
        log.info("Test user created: user@test.com / user123");
    }

    private void initEvents() {
        if (eventRepository.count() > 0) {
            log.info("Events already exist, skipping...");
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // 이벤트 1: 판매중
        Event event1 = Event.builder()
                .name("2025 BTS 월드투어 콘서트")
                .description("BTS의 새로운 월드투어! 서울 공연")
                .startDateTime(now.plusDays(30))
                .saleStartDateTime(now.minusDays(1))
                .saleEndDateTime(now.plusDays(25))
                .status(EventStatus.ON_SALE)
                .totalTickets(1000)
                .price(150000)
                .venue("잠실 종합운동장")
                .build();

        // 이벤트 2: 판매중
        Event event2 = Event.builder()
                .name("뮤지컬 오페라의 유령")
                .description("세계적인 뮤지컬 오페라의 유령 내한 공연")
                .startDateTime(now.plusDays(60))
                .saleStartDateTime(now.minusDays(5))
                .saleEndDateTime(now.plusDays(55))
                .status(EventStatus.ON_SALE)
                .totalTickets(500)
                .price(120000)
                .venue("블루스퀘어 신한카드홀")
                .build();

        // 이벤트 3: 판매중 (저렴한 티켓)
        Event event3 = Event.builder()
                .name("개그 콘서트 2025")
                .description("웃음이 필요한 당신을 위한 개그 콘서트")
                .startDateTime(now.plusDays(14))
                .saleStartDateTime(now.minusDays(3))
                .saleEndDateTime(now.plusDays(10))
                .status(EventStatus.ON_SALE)
                .totalTickets(300)
                .price(50000)
                .venue("KBS홀")
                .build();

        // 이벤트 4: 판매중 (부하테스트용 - 티켓 적음)
        Event event4 = Event.builder()
                .name("한정판 팬미팅")
                .description("50명만을 위한 특별한 팬미팅")
                .startDateTime(now.plusDays(7))
                .saleStartDateTime(now.minusHours(1))
                .saleEndDateTime(now.plusDays(5))
                .status(EventStatus.ON_SALE)
                .totalTickets(50)
                .price(200000)
                .venue("코엑스 컨퍼런스룸")
                .build();

        eventRepository.save(event1);
        eventRepository.save(event2);
        eventRepository.save(event3);
        eventRepository.save(event4);

        log.info("4 sample events created");
    }
}

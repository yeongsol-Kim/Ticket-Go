package com.yeongsol.ticketgo.web;

import com.yeongsol.ticketgo.config.security.SecurityUtil;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.model.MemberRole;
import com.yeongsol.ticketgo.domain.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final EventRepository eventRepository;
    private final MemberService memberService;

    // 관리자 권한 체크 (JWT 기반)
    private boolean isAdmin() {
        try {
            Long memberId = SecurityUtil.getCurrentMemberId();
            if (memberId == null) return false;

            Member member = memberService.findById(memberId);
            return member.getRole() == MemberRole.ADMIN;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 대시보드 ====================

    @GetMapping("")
    public String dashboard(Model model) {
        if (!isAdmin()) {
            return "redirect:/login";
        }

        List<Event> events = eventRepository.findAll();
        model.addAttribute("events", events);
        model.addAttribute("totalEvents", events.size());
        model.addAttribute("onSaleEvents", events.stream()
                .filter(e -> e.getStatus() == EventStatus.ON_SALE).count());

        return "admin/dashboard";
    }

    // ==================== 이벤트 관리 ====================

    @GetMapping("/events")
    public String eventList(Model model) {
        if (!isAdmin()) {
            return "redirect:/login";
        }

        List<Event> events = eventRepository.findAll();
        model.addAttribute("events", events);
        return "admin/events";
    }

    @GetMapping("/events/new")
    public String newEventForm(Model model) {
        if (!isAdmin()) {
            return "redirect:/login";
        }

        model.addAttribute("statuses", EventStatus.values());
        return "admin/event-form";
    }

    @PostMapping("/events")
    public String createEvent(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime startDateTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime saleStartDateTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime saleEndDateTime,
            @RequestParam String status,
            @RequestParam Integer totalTickets,
            @RequestParam Integer price,
            @RequestParam String venue,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin()) {
            return "redirect:/login";
        }

        try {
            Event event = Event.builder()
                    .name(name)
                    .description(description)
                    .startDateTime(startDateTime)
                    .saleStartDateTime(saleStartDateTime)
                    .saleEndDateTime(saleEndDateTime)
                    .status(EventStatus.valueOf(status))
                    .totalTickets(totalTickets)
                    .price(price)
                    .venue(venue)
                    .build();

            eventRepository.save(event);
            redirectAttributes.addFlashAttribute("success", "이벤트가 생성되었습니다.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/admin/events";
    }

    @GetMapping("/events/{id}/edit")
    public String editEventForm(@PathVariable Long id, Model model) {
        if (!isAdmin()) {
            return "redirect:/login";
        }

        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("이벤트를 찾을 수 없습니다."));

        model.addAttribute("event", event);
        model.addAttribute("statuses", EventStatus.values());
        return "admin/event-form";
    }

    @PostMapping("/events/{id}")
    public String updateEvent(
            @PathVariable Long id,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime startDateTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime saleStartDateTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime saleEndDateTime,
            @RequestParam String status,
            @RequestParam Integer totalTickets,
            @RequestParam Integer price,
            @RequestParam String venue,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin()) {
            return "redirect:/login";
        }

        try {
            Event event = eventRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("이벤트를 찾을 수 없습니다."));

            // Event는 불변객체라 새로 만들어야 함 - 직접 업데이트 메서드 필요
            // 임시로 삭제 후 재생성
            eventRepository.delete(event);

            Event updatedEvent = Event.builder()
                    .name(name)
                    .description(description)
                    .startDateTime(startDateTime)
                    .saleStartDateTime(saleStartDateTime)
                    .saleEndDateTime(saleEndDateTime)
                    .status(EventStatus.valueOf(status))
                    .totalTickets(totalTickets)
                    .price(price)
                    .venue(venue)
                    .build();

            eventRepository.save(updatedEvent);
            redirectAttributes.addFlashAttribute("success", "이벤트가 수정되었습니다.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/admin/events";
    }

    @PostMapping("/events/{id}/delete")
    public String deleteEvent(@PathVariable Long id,
                              RedirectAttributes redirectAttributes) {
        if (!isAdmin()) {
            return "redirect:/login";
        }

        try {
            eventRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "이벤트가 삭제되었습니다.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/admin/events";
    }

    @PostMapping("/events/{id}/status")
    public String updateEventStatus(@PathVariable Long id,
                                    @RequestParam String status,
                                    RedirectAttributes redirectAttributes) {
        if (!isAdmin()) {
            return "redirect:/login";
        }

        try {
            Event event = eventRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("이벤트를 찾을 수 없습니다."));
            event.updateStatus(EventStatus.valueOf(status));
            eventRepository.save(event);
            redirectAttributes.addFlashAttribute("success", "상태가 변경되었습니다.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/admin/events";
    }
}

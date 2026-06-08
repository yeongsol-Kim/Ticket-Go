package com.yeongsol.ticketgo.web;

import com.yeongsol.ticketgo.common.exception.BusinessException;
import com.yeongsol.ticketgo.common.exception.ErrorCode;
import com.yeongsol.ticketgo.config.security.JwtAuthenticationFilter;
import com.yeongsol.ticketgo.config.security.SecurityUtil;
import com.yeongsol.ticketgo.domain.auth.service.AuthService;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.service.EventService;
import com.yeongsol.ticketgo.domain.member.service.MemberService;
import com.yeongsol.ticketgo.domain.payment.model.Payment;
import com.yeongsol.ticketgo.domain.payment.model.PaymentMethod;
import com.yeongsol.ticketgo.domain.payment.service.PaymentService;
import com.yeongsol.ticketgo.domain.queue.dto.QueueDto;
import com.yeongsol.ticketgo.domain.queue.service.QueueService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ViewController {

    private final MemberService memberService;
    private final EventService eventService;
    private final QueueService queueService;
    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final AuthService authService;

    // ==================== 메인 페이지 ====================

    @GetMapping("/")
    public String index(Model model) {
        List<Event> events = eventService.getOnSaleEvents();
        model.addAttribute("events", events);
        return "index";
    }

    // ==================== 인증 ====================

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        HttpServletResponse response,
                        RedirectAttributes redirectAttributes) {
        try {
            String token = authService.login(email, password);
            Cookie cookie = new Cookie(JwtAuthenticationFilter.JWT_COOKIE_NAME, token);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(60 * 60 * 24);
            response.addCookie(cookie);
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "이메일 또는 비밀번호가 일치하지 않습니다.");
            return "redirect:/login";
        }
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam String password,
                           @RequestParam String name,
                           @RequestParam(required = false) String phoneNumber,
                           RedirectAttributes redirectAttributes) {
        try {
            memberService.register(email, password, name, phoneNumber);
            redirectAttributes.addFlashAttribute("success", "회원가입이 완료되었습니다. 로그인해주세요.");
            return "redirect:/login";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/register";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(JwtAuthenticationFilter.JWT_COOKIE_NAME, null);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return "redirect:/";
    }

    // ==================== 대기열 ====================

    /**
     * 대기열 페이지 조회
     * - 결제 세션 있으면 결제 페이지로 즉시 redirect
     * - 대기열에 있으면 순번 표시
     * - 대기열에 없으면 티켓 수량 선택 폼 표시
     */
    @GetMapping("/events/{eventId}/queue")
    public String queuePage(@PathVariable Long eventId,
                            Model model,
                            RedirectAttributes redirectAttributes) {
        Long memberId = getCurrentMemberId();
        if (memberId == null) {
            redirectAttributes.addFlashAttribute("error", "로그인이 필요합니다.");
            return "redirect:/login";
        }

        // 결제 세션이 이미 있으면 결제 페이지로 바로 이동
        if (queueService.hasPaymentSession(eventId, memberId)) {
            Long bookingId = queueService.getPaymentSessionBookingId(eventId, memberId);
            return "redirect:/bookings/" + bookingId + "/payment";
        }

        Event event = eventService.findById(eventId);
        model.addAttribute("event", event);

        // 대기열 진입 여부 확인
        try {
            QueueDto.StatusResponse status = queueService.getQueueStatus(eventId, memberId);
            model.addAttribute("inQueue", true);
            model.addAttribute("position", status.getPosition());
            model.addAttribute("totalWaiting", status.getTotalWaiting());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.NOT_IN_QUEUE) {
                model.addAttribute("inQueue", false);
            } else {
                throw e;
            }
        }

        return "queue";
    }

    /**
     * 대기열 진입 - 티켓 수량 선택 후 폼 submit
     */
    @PostMapping("/events/{eventId}/queue")
    public String enterQueue(@PathVariable Long eventId,
                             @RequestParam int ticketCount,
                             RedirectAttributes redirectAttributes) {
        Long memberId = getCurrentMemberId();
        if (memberId == null) {
            return "redirect:/login";
        }

        try {
            queueService.enterQueue(eventId, memberId, ticketCount);
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/events/" + eventId + "/queue";
    }

    // ==================== 결제 ====================

    @GetMapping("/bookings/{bookingId}/payment")
    public String paymentPage(@PathVariable Long bookingId, Model model) {
        Long memberId = getCurrentMemberId();
        if (memberId == null) return "redirect:/login";

        Booking booking = bookingService.findById(bookingId);
        Event event = eventService.findById(booking.getEventId());

        model.addAttribute("booking", booking);
        model.addAttribute("event", event);
        return "payment";
    }

    @PostMapping("/bookings/{bookingId}/pay")
    public String processPayment(@PathVariable Long bookingId,
                                 @RequestParam String paymentMethod,
                                 RedirectAttributes redirectAttributes) {
        Long memberId = getCurrentMemberId();
        if (memberId == null) { return "redirect:/login"; }

        // 결제 세션 검증
        Booking booking = bookingService.findById(bookingId);
        if (!queueService.hasPaymentSession(booking.getEventId(), memberId)) {
            redirectAttributes.addFlashAttribute("error", "결제 세션이 만료되었습니다. 다시 대기열에 입장해주세요.");
            return "redirect:/events/" + booking.getEventId() + "/queue";
        }

        try {
            PaymentMethod method = PaymentMethod.valueOf(paymentMethod);
            Payment payment = paymentService.requestPayment(bookingId, method);

            // 결제 세션 제거
            queueService.removePaymentSession(booking.getEventId(), memberId);

            // 결제 승인 (실제로는 PG 연동)
            String paymentKey = "PG_" + System.currentTimeMillis();
            paymentService.approvePayment(payment.getId(), paymentKey);

            return "redirect:/bookings/" + bookingId + "/complete";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/bookings/" + bookingId + "/payment";
        }
    }

    @GetMapping("/bookings/{bookingId}/complete")
    public String completePage(@PathVariable Long bookingId, Model model) {
        Long memberId = getCurrentMemberId();
        if (memberId == null) return "redirect:/login";

        Booking booking = bookingService.findById(bookingId);
        Event event = eventService.findById(booking.getEventId());

        model.addAttribute("booking", booking);
        model.addAttribute("event", event);
        return "complete";
    }

    // ==================== 내 예매 ====================

    @GetMapping("/my-bookings")
    public String myBookingsPage(Model model) {
        Long memberId = getCurrentMemberId();
        if (memberId == null) return "redirect:/login";

        List<Booking> bookings = bookingService.getMyBookings(memberId);
        List<BookingWithEvent> bookingsWithEvents = bookings.stream()
                .map(booking -> {
                    Event event = eventService.findById(booking.getEventId());
                    return new BookingWithEvent(booking, event);
                })
                .collect(Collectors.toList());

        model.addAttribute("bookings", bookingsWithEvents);
        return "my-bookings";
    }

    public record BookingWithEvent(Booking booking, Event event) {}

    // ==================== API (대기열 상태 조회용 폴링) ====================

    @GetMapping("/api/queue/{eventId}/status")
    @ResponseBody
    public QueueDto.StatusResponse getQueueStatus(@PathVariable Long eventId) {
        Long memberId = getCurrentMemberId();
        if (memberId == null) {
            throw new RuntimeException("로그인이 필요합니다.");
        }
        return queueService.getQueueStatus(eventId, memberId);
    }

    // ==================== Helper ====================

    private Long getCurrentMemberId() {
        try {
            return SecurityUtil.getCurrentMemberId();
        } catch (Exception e) {
            return null;
        }
    }
}

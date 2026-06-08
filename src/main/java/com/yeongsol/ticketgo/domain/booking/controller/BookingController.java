package com.yeongsol.ticketgo.domain.booking.controller;

import com.yeongsol.ticketgo.config.security.SecurityUtil;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Booking", description = "예약 API")
@Slf4j
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @Operation(summary = "내 예약 목록 조회",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @GetMapping("/me")
    public ResponseEntity<?> getMyBookings() {
        Long memberId = SecurityUtil.getCurrentMemberId();
        List<Booking> bookings = bookingService.getMyBookings(memberId);
        List<BookingResponse> response = bookings.stream()
                .map(BookingResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "예약 상세 조회",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @GetMapping("/{bookingNumber}")
    public ResponseEntity<?> getBooking(@PathVariable String bookingNumber) {
        Booking booking = bookingService.findByBookingNumber(bookingNumber);
        return ResponseEntity.ok(BookingResponse.from(booking));
    }

    @Operation(summary = "예약 취소",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelBooking(@PathVariable Long id) {
        bookingService.cancelBooking(id);
        return ResponseEntity.ok(new SuccessResponse("예약이 취소되었습니다"));
    }

    record BookingResponse(
            Long id,
            String bookingNumber,
            Long eventId,
            String status,
            Integer ticketCount,
            Integer totalAmount,
            LocalDateTime bookedAt,
            LocalDateTime expiresAt,
            LocalDateTime confirmedAt
    ) {
        static BookingResponse from(Booking booking) {
            return new BookingResponse(
                    booking.getId(),
                    booking.getBookingNumber(),
                    booking.getEventId(),
                    booking.getStatus().name(),
                    booking.getTicketCount(),
                    booking.getTotalAmount(),
                    booking.getBookedAt(),
                    booking.getExpiresAt(),
                    booking.getConfirmedAt()
            );
        }
    }

    record SuccessResponse(String message) {}
}

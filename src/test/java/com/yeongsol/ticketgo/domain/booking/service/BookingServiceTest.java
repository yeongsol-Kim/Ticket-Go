package com.yeongsol.ticketgo.domain.booking.service;

import com.yeongsol.ticketgo.domain.booking.exception.BookingNotFoundException;
import com.yeongsol.ticketgo.domain.booking.model.Booking;
import com.yeongsol.ticketgo.domain.booking.model.BookingStatus;
import com.yeongsol.ticketgo.domain.booking.repository.BookingRepository;
import com.yeongsol.ticketgo.domain.event.exception.EventNotFoundException;
import com.yeongsol.ticketgo.domain.event.model.Event;
import com.yeongsol.ticketgo.domain.event.model.EventStatus;
import com.yeongsol.ticketgo.domain.event.repository.EventRepository;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * BookingService 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService 단위 테스트")
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private BookingService bookingService;

    @Nested
    @DisplayName("createBooking 테스트")
    class CreateBookingTest {

        @Test
        @DisplayName("예약 생성 성공 - 재고 차감 및 티켓 생성")
        void createBooking_success() {
            // Given
            Long memberId = 1L;
            Long eventId = 1L;
            int ticketCount = 2;

            Event mockEvent = createMockEvent(eventId, 100, 50000);
            given(eventRepository.findById(eventId)).willReturn(Optional.of(mockEvent));
            given(bookingRepository.save(any(Booking.class))).willAnswer(invocation -> {
                Booking booking = invocation.getArgument(0);
                setBookingId(booking, 1L);
                return booking;
            });

            // When
            Booking result = bookingService.createBooking(memberId, eventId, ticketCount, 600L);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getMemberId()).isEqualTo(memberId);
            assertThat(result.getEventId()).isEqualTo(eventId);
            assertThat(result.getTicketCount()).isEqualTo(ticketCount);
            assertThat(result.getTotalAmount()).isEqualTo(50000 * ticketCount);
            assertThat(result.getStatus()).isEqualTo(BookingStatus.RESERVED);
            assertThat(mockEvent.getAvailableTickets()).isEqualTo(98); // 100 - 2

            then(eventRepository).should(times(1)).findById(eventId);
            then(bookingRepository).should(times(1)).save(any(Booking.class));
        }

        @Test
        @DisplayName("예약 생성 실패 - 존재하지 않는 이벤트")
        void createBooking_eventNotFound_throwsException() {
            // Given
            Long memberId = 1L;
            Long eventId = 999L;
            int ticketCount = 2;

            given(eventRepository.findById(eventId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bookingService.createBooking(memberId, eventId, ticketCount, 600L))
                    .isInstanceOf(EventNotFoundException.class);

            then(bookingRepository).should(never()).save(any(Booking.class));
            then(ticketRepository).should(never()).saveAll(anyList());
        }

        @Test
        @DisplayName("예약 생성 실패 - 재고 부족")
        void createBooking_notEnoughTickets_throwsException() {
            // Given
            Long memberId = 1L;
            Long eventId = 1L;
            int ticketCount = 2;

            Event mockEvent = createMockEvent(eventId, 1, 50000); // 1장만 남음
            given(eventRepository.findById(eventId)).willReturn(Optional.of(mockEvent));

            // When & Then
            assertThatThrownBy(() -> bookingService.createBooking(memberId, eventId, ticketCount, 600L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Not enough available tickets");

            then(bookingRepository).should(never()).save(any(Booking.class));
        }
    }

    @Nested
    @DisplayName("findByBookingNumber 테스트")
    class FindByBookingNumberTest {

        @Test
        @DisplayName("예약 번호로 조회 성공")
        void findByBookingNumber_success() {
            // Given
            String bookingNumber = "test-booking-number";
            Booking mockBooking = createMockBooking(1L, 1L, 1L, bookingNumber);
            given(bookingRepository.findByBookingNumber(bookingNumber))
                    .willReturn(Optional.of(mockBooking));

            // When
            Booking result = bookingService.findByBookingNumber(bookingNumber);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getBookingNumber()).isEqualTo(bookingNumber);
            then(bookingRepository).should(times(1)).findByBookingNumber(bookingNumber);
        }

        @Test
        @DisplayName("예약 번호로 조회 실패 - 존재하지 않는 예약 번호")
        void findByBookingNumber_notFound_throwsException() {
            // Given
            String bookingNumber = "not-found-booking";
            given(bookingRepository.findByBookingNumber(bookingNumber))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bookingService.findByBookingNumber(bookingNumber))
                    .isInstanceOf(BookingNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("findById 테스트")
    class FindByIdTest {

        @Test
        @DisplayName("예약 ID로 조회 성공")
        void findById_success() {
            // Given
            Long bookingId = 1L;
            Booking mockBooking = createMockBooking(bookingId, 1L, 1L, "booking-number");
            given(bookingRepository.findById(bookingId)).willReturn(Optional.of(mockBooking));

            // When
            Booking result = bookingService.findById(bookingId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(bookingId);
            then(bookingRepository).should(times(1)).findById(bookingId);
        }

        @Test
        @DisplayName("예약 ID로 조회 실패 - 존재하지 않는 ID")
        void findById_notFound_throwsException() {
            // Given
            Long bookingId = 999L;
            given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bookingService.findById(bookingId))
                    .isInstanceOf(BookingNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getMyBookings 테스트")
    class GetMyBookingsTest {

        @Test
        @DisplayName("회원의 예약 목록 조회 성공")
        void getMyBookings_success() {
            // Given
            Long memberId = 1L;
            List<Booking> mockBookings = List.of(
                    createMockBooking(1L, memberId, 1L, "booking-1"),
                    createMockBooking(2L, memberId, 2L, "booking-2")
            );
            given(bookingRepository.findByMemberIdOrderByCreatedAtDesc(memberId))
                    .willReturn(mockBookings);

            // When
            List<Booking> result = bookingService.getMyBookings(memberId);

            // Then
            assertThat(result).hasSize(2);
            then(bookingRepository).should(times(1))
                    .findByMemberIdOrderByCreatedAtDesc(memberId);
        }

        @Test
        @DisplayName("회원의 예약 목록 조회 - 예약 없음")
        void getMyBookings_empty() {
            // Given
            Long memberId = 1L;
            given(bookingRepository.findByMemberIdOrderByCreatedAtDesc(memberId))
                    .willReturn(List.of());

            // When
            List<Booking> result = bookingService.getMyBookings(memberId);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("cancelBooking 테스트")
    class CancelBookingTest {

        @Test
        @DisplayName("예약 취소 성공 - 티켓 취소 및 재고 복구")
        void cancelBooking_success() {
            // Given
            Long bookingId = 1L;
            Long eventId = 1L;
            int ticketCount = 2;

            Booking mockBooking = createMockBooking(bookingId, 1L, eventId, "booking-number");
            setBookingTicketCount(mockBooking, ticketCount);

            List<Ticket> mockTickets = List.of(
                    createMockTicket(1L, eventId, bookingId),
                    createMockTicket(2L, eventId, bookingId)
            );

            given(bookingRepository.findById(bookingId)).willReturn(Optional.of(mockBooking));
            given(ticketRepository.findByBookingId(bookingId)).willReturn(mockTickets);

            // When
            bookingService.cancelBooking(bookingId);

            // Then
            assertThat(mockBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            // 재고 복원은 원자적 벌크 UPDATE로 수행 (엔티티 변경 아님)
            then(eventRepository).should(times(1)).increaseStock(eventId, ticketCount);

            then(bookingRepository).should(times(1)).findById(bookingId);
            then(ticketRepository).should(times(1)).findByBookingId(bookingId);
        }

        @Test
        @DisplayName("예약 취소 실패 - 이미 확정된 예약")
        void cancelBooking_confirmedBooking_throwsException() {
            // Given
            Long bookingId = 1L;
            Booking mockBooking = createMockBooking(bookingId, 1L, 1L, "booking-number");
            mockBooking.confirm(); // 확정 상태로 변경

            given(bookingRepository.findById(bookingId)).willReturn(Optional.of(mockBooking));

            // When & Then
            assertThatThrownBy(() -> bookingService.cancelBooking(bookingId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot cancel confirmed booking");

            then(ticketRepository).should(never()).findByBookingId(any());
            then(eventRepository).should(never()).findById(any());
        }
    }

    @Nested
    @DisplayName("expireBookings 테스트")
    class ExpireBookingsTest {

        @Test
        @DisplayName("만료된 예약 배치 처리 성공")
        void expireBookings_success() {
            // Given
            Booking expiredBooking1 = createMockBooking(1L, 1L, 1L, "booking-1");
            Booking expiredBooking2 = createMockBooking(2L, 2L, 1L, "booking-2");
            setBookingTicketCount(expiredBooking1, 2);
            setBookingTicketCount(expiredBooking2, 3);

            List<Booking> expiredBookings = List.of(expiredBooking1, expiredBooking2);

            given(bookingRepository.findExpiredBookings(eq(BookingStatus.RESERVED), any(LocalDateTime.class)))
                    .willReturn(expiredBookings);
            given(ticketRepository.findByBookingId(1L)).willReturn(List.of(
                    createMockTicket(1L, 1L, 1L),
                    createMockTicket(2L, 1L, 1L)
            ));
            given(ticketRepository.findByBookingId(2L)).willReturn(List.of(
                    createMockTicket(3L, 1L, 2L),
                    createMockTicket(4L, 1L, 2L),
                    createMockTicket(5L, 1L, 2L)
            ));

            // When
            bookingService.expireBookings();

            // Then
            assertThat(expiredBooking1.getStatus()).isEqualTo(BookingStatus.EXPIRED);
            assertThat(expiredBooking2.getStatus()).isEqualTo(BookingStatus.EXPIRED);
            // 재고 복원은 원자적 벌크 UPDATE로 각 예약분(2장, 3장) 수행
            then(eventRepository).should(times(1)).increaseStock(1L, 2);
            then(eventRepository).should(times(1)).increaseStock(1L, 3);

            then(bookingRepository).should(times(1))
                    .findExpiredBookings(eq(BookingStatus.RESERVED), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("만료된 예약 없음 - 처리 건수 0")
        void expireBookings_noExpiredBookings() {
            // Given
            given(bookingRepository.findExpiredBookings(eq(BookingStatus.RESERVED), any(LocalDateTime.class)))
                    .willReturn(List.of());

            // When
            bookingService.expireBookings();

            // Then
            then(ticketRepository).should(never()).findByBookingId(any());
            then(eventRepository).should(never()).findById(any());
        }
    }

    @Nested
    @DisplayName("hasDuplicateBooking 테스트")
    class HasDuplicateBookingTest {

        @Test
        @DisplayName("중복 예약 존재")
        void hasDuplicateBooking_true() {
            // Given
            Long memberId = 1L;
            Long eventId = 1L;
            given(bookingRepository.existsByMemberIdAndEventIdAndStatusIn(
                    eq(memberId), eq(eventId), anyList()
            )).willReturn(true);

            // When
            boolean result = bookingService.hasDuplicateBooking(memberId, eventId);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("중복 예약 없음")
        void hasDuplicateBooking_false() {
            // Given
            Long memberId = 1L;
            Long eventId = 1L;
            given(bookingRepository.existsByMemberIdAndEventIdAndStatusIn(
                    eq(memberId), eq(eventId), anyList()
            )).willReturn(false);

            // When
            boolean result = bookingService.hasDuplicateBooking(memberId, eventId);

            // Then
            assertThat(result).isFalse();
        }
    }

    // ===== Helper Methods =====

    private Event createMockEvent(Long id, int availableTickets, int price) {
        LocalDateTime now = LocalDateTime.now();
        Event event = Event.builder()
                .name("테스트 이벤트")
                .description("테스트")
                .startDateTime(now.plusDays(30))
                .saleStartDateTime(now.minusHours(1))
                .saleEndDateTime(now.plusDays(29))
                .status(EventStatus.ON_SALE)
                .totalTickets(100)
                .price(price)
                .venue("테스트 공연장")
                .build();

        setFieldValue(event, "id", id);
        setFieldValue(event, "availableTickets", availableTickets);
        return event;
    }

    private Booking createMockBooking(Long id, Long memberId, Long eventId, String bookingNumber) {
        Booking booking = Booking.builder()
                .memberId(memberId)
                .eventId(eventId)
                .bookingNumber(bookingNumber)
                .ticketCount(1)
                .totalAmount(50000)
                .timeoutSeconds(600)
                .build();

        setFieldValue(booking, "id", id);
        return booking;
    }

    private Ticket createMockTicket(Long id, Long eventId, Long bookingId) {
        Ticket ticket = Ticket.builder()
                .eventId(eventId)
                .bookingId(bookingId)
                .ticketNumber("ticket-" + id)
                .build();

        setFieldValue(ticket, "id", id);
        return ticket;
    }

    private void setBookingId(Booking booking, Long id) {
        setFieldValue(booking, "id", id);
    }

    private void setBookingTicketCount(Booking booking, int ticketCount) {
        setFieldValue(booking, "ticketCount", ticketCount);
    }

    private void setFieldValue(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

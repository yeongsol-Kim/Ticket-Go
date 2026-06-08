package com.yeongsol.ticketgo.domain.ticket.service;

import com.yeongsol.ticketgo.domain.ticket.exception.TicketNotFoundException;
import com.yeongsol.ticketgo.domain.ticket.model.Ticket;
import com.yeongsol.ticketgo.domain.ticket.model.TicketStatus;
import com.yeongsol.ticketgo.domain.ticket.repository.TicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * TicketService 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TicketService 단위 테스트")
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private TicketService ticketService;

    @Nested
    @DisplayName("findByTicketNumber 테스트")
    class FindByTicketNumberTest {

        @Test
        @DisplayName("티켓 번호로 조회 성공")
        void findByTicketNumber_success() {
            // Given
            String ticketNumber = "ticket-123";
            Ticket mockTicket = createMockTicket(1L, 1L, 1L, ticketNumber);
            given(ticketRepository.findByTicketNumber(ticketNumber))
                    .willReturn(Optional.of(mockTicket));

            // When
            Ticket result = ticketService.findByTicketNumber(ticketNumber);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTicketNumber()).isEqualTo(ticketNumber);
            then(ticketRepository).should(times(1)).findByTicketNumber(ticketNumber);
        }

        @Test
        @DisplayName("티켓 번호로 조회 실패 - 존재하지 않는 티켓")
        void findByTicketNumber_notFound_throwsException() {
            // Given
            String ticketNumber = "not-found-ticket";
            given(ticketRepository.findByTicketNumber(ticketNumber))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> ticketService.findByTicketNumber(ticketNumber))
                    .isInstanceOf(TicketNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("findById 테스트")
    class FindByIdTest {

        @Test
        @DisplayName("티켓 ID로 조회 성공")
        void findById_success() {
            // Given
            Long ticketId = 1L;
            Ticket mockTicket = createMockTicket(ticketId, 1L, 1L, "ticket-123");
            given(ticketRepository.findById(ticketId)).willReturn(Optional.of(mockTicket));

            // When
            Ticket result = ticketService.findById(ticketId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(ticketId);
        }

        @Test
        @DisplayName("티켓 ID로 조회 실패 - 존재하지 않는 ID")
        void findById_notFound_throwsException() {
            // Given
            Long ticketId = 999L;
            given(ticketRepository.findById(ticketId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> ticketService.findById(ticketId))
                    .isInstanceOf(TicketNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getTicketsByBooking 테스트")
    class GetTicketsByBookingTest {

        @Test
        @DisplayName("예약별 티켓 조회 성공")
        void getTicketsByBooking_success() {
            // Given
            Long bookingId = 1L;
            List<Ticket> mockTickets = List.of(
                    createMockTicket(1L, 1L, bookingId, "ticket-1"),
                    createMockTicket(2L, 1L, bookingId, "ticket-2")
            );
            given(ticketRepository.findByBookingId(bookingId)).willReturn(mockTickets);

            // When
            List<Ticket> result = ticketService.getTicketsByBooking(bookingId);

            // Then
            assertThat(result).hasSize(2);
            then(ticketRepository).should(times(1)).findByBookingId(bookingId);
        }

        @Test
        @DisplayName("예약별 티켓 조회 - 티켓 없음")
        void getTicketsByBooking_empty() {
            // Given
            Long bookingId = 1L;
            given(ticketRepository.findByBookingId(bookingId)).willReturn(List.of());

            // When
            List<Ticket> result = ticketService.getTicketsByBooking(bookingId);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getMyTickets 테스트")
    class GetMyTicketsTest {

        @Test
        @DisplayName("회원의 티켓 목록 조회 성공")
        void getMyTickets_success() {
            // Given
            Long memberId = 1L;
            List<Ticket> mockTickets = List.of(
                    createMockTicket(1L, 1L, 1L, "ticket-1"),
                    createMockTicket(2L, 2L, 2L, "ticket-2")
            );
            given(ticketRepository.findByMemberId(memberId)).willReturn(mockTickets);

            // When
            List<Ticket> result = ticketService.getMyTickets(memberId);

            // Then
            assertThat(result).hasSize(2);
            then(ticketRepository).should(times(1)).findByMemberId(memberId);
        }
    }

    @Nested
    @DisplayName("getMyTicketsForEvent 테스트")
    class GetMyTicketsForEventTest {

        @Test
        @DisplayName("회원의 특정 이벤트 티켓 조회 성공")
        void getMyTicketsForEvent_success() {
            // Given
            Long memberId = 1L;
            Long eventId = 1L;
            List<Ticket> mockTickets = List.of(
                    createMockTicket(1L, eventId, 1L, "ticket-1"),
                    createMockTicket(2L, eventId, 1L, "ticket-2")
            );
            given(ticketRepository.findByMemberIdAndEventId(memberId, eventId))
                    .willReturn(mockTickets);

            // When
            List<Ticket> result = ticketService.getMyTicketsForEvent(memberId, eventId);

            // Then
            assertThat(result).hasSize(2);
            then(ticketRepository).should(times(1))
                    .findByMemberIdAndEventId(memberId, eventId);
        }
    }

    @Nested
    @DisplayName("validateTicket 테스트")
    class ValidateTicketTest {

        @Test
        @DisplayName("티켓 검증 성공")
        void validateTicket_success() {
            // Given
            String ticketNumber = "ticket-123";
            Long eventId = 1L;
            Ticket mockTicket = createMockTicket(1L, eventId, 1L, ticketNumber);
            given(ticketRepository.findByTicketNumber(ticketNumber))
                    .willReturn(Optional.of(mockTicket));

            // When
            boolean result = ticketService.validateTicket(ticketNumber, eventId);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("티켓 검증 실패 - 취소된 티켓")
        void validateTicket_cancelledTicket_returnsFalse() {
            // Given
            String ticketNumber = "ticket-123";
            Long eventId = 1L;
            Ticket mockTicket = createMockTicket(1L, eventId, 1L, ticketNumber);
            mockTicket.cancel(); // 티켓 취소

            given(ticketRepository.findByTicketNumber(ticketNumber))
                    .willReturn(Optional.of(mockTicket));

            // When
            boolean result = ticketService.validateTicket(ticketNumber, eventId);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("티켓 검증 실패 - 이벤트 불일치")
        void validateTicket_eventMismatch_returnsFalse() {
            // Given
            String ticketNumber = "ticket-123";
            Long ticketEventId = 1L;
            Long requestEventId = 2L; // 다른 이벤트
            Ticket mockTicket = createMockTicket(1L, ticketEventId, 1L, ticketNumber);

            given(ticketRepository.findByTicketNumber(ticketNumber))
                    .willReturn(Optional.of(mockTicket));

            // When
            boolean result = ticketService.validateTicket(ticketNumber, requestEventId);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("티켓 검증 실패 - 존재하지 않는 티켓")
        void validateTicket_ticketNotFound_throwsException() {
            // Given
            String ticketNumber = "not-found-ticket";
            Long eventId = 1L;
            given(ticketRepository.findByTicketNumber(ticketNumber))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> ticketService.validateTicket(ticketNumber, eventId))
                    .isInstanceOf(TicketNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getBookedTicketCount 테스트")
    class GetBookedTicketCountTest {

        @Test
        @DisplayName("발급된 티켓 수 조회")
        void getBookedTicketCount_success() {
            // Given
            Long eventId = 1L;
            given(ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.BOOKED))
                    .willReturn(50L);

            // When
            long result = ticketService.getBookedTicketCount(eventId);

            // Then
            assertThat(result).isEqualTo(50L);
        }
    }

    @Nested
    @DisplayName("getCancelledTicketCount 테스트")
    class GetCancelledTicketCountTest {

        @Test
        @DisplayName("취소된 티켓 수 조회")
        void getCancelledTicketCount_success() {
            // Given
            Long eventId = 1L;
            given(ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.CANCELLED))
                    .willReturn(10L);

            // When
            long result = ticketService.getCancelledTicketCount(eventId);

            // Then
            assertThat(result).isEqualTo(10L);
        }
    }

    // ===== Helper Methods =====

    private Ticket createMockTicket(Long id, Long eventId, Long bookingId, String ticketNumber) {
        Ticket ticket = Ticket.builder()
                .eventId(eventId)
                .bookingId(bookingId)
                .ticketNumber(ticketNumber)
                .build();

        setFieldValue(ticket, "id", id);
        return ticket;
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

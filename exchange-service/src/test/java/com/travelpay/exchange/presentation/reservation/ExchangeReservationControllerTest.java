package com.travelpay.exchange.presentation.reservation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.exchange.application.reservation.ExchangeReservationService;
import com.travelpay.exchange.application.reservation.ExchangeReservationView;
import com.travelpay.exchange.application.reservation.ReserveCommand;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ReservationPolicy;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExchangeReservationControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-17T03:00:00Z");

    private final ExchangeReservationService reservationService = mock(ExchangeReservationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ExchangeReservationController(reservationService))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver(
                    () -> Optional.of(new AuthenticatedUser(USER_ID, AuthRole.USER))))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void reservesWithIdempotencyKeyAndReturnsCreated() throws Exception {
        when(reservationService.reserve(any())).thenReturn(new ExchangeReservationView(buyYen(), null));

        mockMvc.perform(post("/api/v1/exchange-reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"walletId":"%s","fromCurrency":"krw","toCurrency":"JPY","amount":"100000",
                                 "targetRate":"830.00","expiresAt":"2026-11-17T03:00:00Z"}
                                """.formatted(WALLET_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reservationId").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.data.type").value("EXCHANGE"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.fromAmount").value("100000"))
                .andExpect(jsonPath("$.data.targetRate").value("830"))
                .andExpect(jsonPath("$.data.rateUnit").value(100))
                .andExpect(jsonPath("$.data.condition").value("AT_OR_BELOW"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-17T03:00:00Z"))
                .andExpect(jsonPath("$.data.orderId").doesNotExist())
                .andExpect(jsonPath("$.data.orderStatus").doesNotExist());

        ArgumentCaptor<ReserveCommand> command = ArgumentCaptor.forClass(ReserveCommand.class);
        verify(reservationService).reserve(command.capture());
        assertEquals(new ReserveCommand(USER_ID, "key-1", WALLET_ID, Currency.KRW, Currency.JPY,
                new BigDecimal("100000"), new BigDecimal("830.00"), EXPIRES_AT), command.getValue());
    }

    /** 형식이 틀린 요청은 서비스에 닿기 전에 400이다 */
    @Test
    void rejectsMalformedRequestsBeforeService() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-reservations").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"walletId":"%s","fromCurrency":"USD","toCurrency":"KRW","amount":"100",
                                 "targetRate":"1300","expiresAt":"2026-11-17T03:00:00Z"}
                                """.formatted(WALLET_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_UNSUPPORTED_CURRENCY"));
        mockMvc.perform(post("/api/v1/exchange-reservations").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"walletId":"%s","fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """.formatted(WALLET_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/exchange-reservations").param("status", "DONE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reservationService);
    }

    @Test
    void listsWithStatusFilter() throws Exception {
        when(reservationService.list(USER_ID, ReservationStatus.WAITING, 0, 20))
                .thenReturn(new PageImpl<>(List.of(buyYen())));

        mockMvc.perform(get("/api/v1/exchange-reservations").param("status", "WAITING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reservationId").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].condition").value("AT_OR_BELOW"))
                .andExpect(jsonPath("$.data.items[0].orderStatus").doesNotExist());
    }

    @Test
    void showsOrderOfExecutedReservation() throws Exception {
        ExchangeReservation executed = buyYen();
        UUID orderId = UUID.randomUUID();
        ReflectionTestUtils.setField(executed, "status", ReservationStatus.EXECUTED);
        ReflectionTestUtils.setField(executed, "orderId", orderId);
        ReflectionTestUtils.setField(executed, "executedAt", NOW.plus(Duration.ofHours(1)));
        when(reservationService.find(USER_ID, RESERVATION_ID))
                .thenReturn(new ExchangeReservationView(executed, ExchangeOrderStatus.COMPLETED));

        mockMvc.perform(get("/api/v1/exchange-reservations/{id}", RESERVATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.orderStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.executedAt").value("2026-11-10T04:00:00Z"));
    }

    @Test
    void cancelsReservation() throws Exception {
        ExchangeReservation canceled = buyYen();
        ReflectionTestUtils.setField(canceled, "status", ReservationStatus.CANCELED);
        when(reservationService.cancel(eq(USER_ID), eq(RESERVATION_ID)))
                .thenReturn(new ExchangeReservationView(canceled, null));

        mockMvc.perform(post("/api/v1/exchange-reservations/{id}/cancel", RESERVATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    private static ExchangeReservation buyYen() {
        ExchangeReservation reservation = ExchangeReservation.reserve(USER_ID, WALLET_ID, new IdempotencyKey("key-1"),
                new QuoteCurrencies(Currency.KRW, Currency.JPY), new Money(Currency.KRW, 100_000),
                new BigDecimal("830.00"), EXPIRES_AT, QuoteFixtures.policy(),
                new ReservationPolicy(Duration.ofDays(30), 10), NOW);
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        return reservation;
    }
}

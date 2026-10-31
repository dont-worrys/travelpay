package com.travelpay.exchange.presentation.order;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.exchange.application.order.ExchangeOrderService;
import com.travelpay.exchange.application.order.PlaceOrderCommand;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExchangeOrderControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-29T03:00:00Z");

    private final ExchangeOrderService orderService = mock(ExchangeOrderService.class);
    private final Optional<AuthenticatedUser> currentUser = Optional.of(new AuthenticatedUser(USER_ID, AuthRole.USER));
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ExchangeOrderController(orderService))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver(() -> currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void acceptsProcessingOrderWith202() throws Exception {
        ExchangeOrder order = order();
        when(orderService.place(any())).thenReturn(order);

        mockMvc.perform(post("/api/v1/exchanges").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quoteId":"%s","walletId":"%s"}
                                """.formatted(order.getQuoteId(), WALLET_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.data.type").value("EXCHANGE"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.failureReason").doesNotExist())
                .andExpect(jsonPath("$.data.fromCurrency").value("KRW"))
                .andExpect(jsonPath("$.data.fromAmount").value("100000"))
                .andExpect(jsonPath("$.data.toCurrency").value("JPY"))
                .andExpect(jsonPath("$.data.toAmount").value("11928"))
                .andExpect(jsonPath("$.data.feeRate").value("0.01"))
                .andExpect(jsonPath("$.data.feeAmount").value("1000"))
                .andExpect(jsonPath("$.data.totalDebitAmount").value("101000"))
                .andExpect(jsonPath("$.data.appliedRate").value("838.3"))
                .andExpect(jsonPath("$.data.rateUnit").value(100))
                .andExpect(jsonPath("$.data.requestedAt").value("2026-10-29T03:01:00Z"))
                .andExpect(jsonPath("$.data.settledAt").doesNotExist());

        verify(orderService).place(new PlaceOrderCommand(USER_ID, "key-1", order.getQuoteId(), WALLET_ID));
    }

    @Test
    void settledOrderIsOk() throws Exception {
        ExchangeOrder order = order();
        ReflectionTestUtils.setField(order, "status", ExchangeOrderStatus.FAILED);
        ReflectionTestUtils.setField(order, "failureReason", ExchangeFailureReason.INSUFFICIENT_BALANCE);
        when(orderService.place(any())).thenReturn(order);

        mockMvc.perform(post("/api/v1/exchanges").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quoteId":"%s","walletId":"%s"}
                                """.formatted(order.getQuoteId(), WALLET_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void passesMissingKeyToServiceForValidation() throws Exception {
        when(orderService.place(any()))
                .thenThrow(new BusinessException(ExchangeOrderErrorCode.INVALID_IDEMPOTENCY_KEY));

        mockMvc.perform(post("/api/v1/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quoteId":"%s","walletId":"%s"}
                                """.formatted(UUID.randomUUID(), WALLET_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void rejectsMissingFields() throws Exception {
        mockMvc.perform(post("/api/v1/exchanges").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.length()").value(2));

        verifyNoInteractions(orderService);
    }

    @Test
    void returnsServiceErrorAsIs() throws Exception {
        when(orderService.place(any())).thenThrow(new BusinessException(ExchangeOrderErrorCode.QUOTE_ALREADY_USED));

        mockMvc.perform(post("/api/v1/exchanges").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quoteId":"%s","walletId":"%s"}
                                """.formatted(UUID.randomUUID(), WALLET_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_ALREADY_USED"));
    }

    @Test
    void getsOwnOrder() throws Exception {
        when(orderService.find(USER_ID, ORDER_ID)).thenReturn(order());

        mockMvc.perform(get("/api/v1/exchanges/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()));
    }

    @Test
    void orderOfAnotherUserIsNotFound() throws Exception {
        when(orderService.find(USER_ID, ORDER_ID))
                .thenThrow(new BusinessException(ExchangeOrderErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/exchanges/{orderId}", ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_ORDER_NOT_FOUND"));
    }

    /** 원화 10만 원 → 엔화, 견적 발급 1분 뒤 신청한 처리 중 주문입니다. */
    private static ExchangeOrder order() {
        ExchangeQuote quote = OrderFixtures.wonToYenQuote(USER_ID,
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), ISSUED_AT);
        ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(quote, "createdAt", ISSUED_AT);
        ExchangeOrder order = OrderFixtures.placed(quote, WALLET_ID, "key-1");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }
}

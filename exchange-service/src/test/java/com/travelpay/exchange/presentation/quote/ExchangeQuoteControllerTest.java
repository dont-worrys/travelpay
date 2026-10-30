package com.travelpay.exchange.presentation.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.exchange.application.quote.ExchangeQuoteService;
import com.travelpay.exchange.application.quote.ExchangeQuoteView;
import com.travelpay.exchange.application.quote.IssueQuoteCommand;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteErrorCode;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuoteType;
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

class ExchangeQuoteControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID QUOTE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");

    private final ExchangeQuoteService quoteService = mock(ExchangeQuoteService.class);
    private Optional<AuthenticatedUser> currentUser = Optional.of(new AuthenticatedUser(USER_ID, AuthRole.USER));
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ExchangeQuoteController(quoteService))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver(() -> currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void issuesQuoteForCurrentUser() throws Exception {
        when(quoteService.issue(any())).thenReturn(new ExchangeQuoteView(yuanToWon(), false));

        mockMvc.perform(post("/api/v1/exchange-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"cny","toCurrency":"KRW","amount":"100.50"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.quoteId").value(QUOTE_ID.toString()))
                .andExpect(jsonPath("$.data.fromCurrency").value("CNY"))
                .andExpect(jsonPath("$.data.fromAmount").value("100.50"))
                .andExpect(jsonPath("$.data.toCurrency").value("KRW"))
                .andExpect(jsonPath("$.data.toAmount").value("19968"))
                .andExpect(jsonPath("$.data.feeRate").value("0.01"))
                .andExpect(jsonPath("$.data.feeAmount").value("1.01"))
                .andExpect(jsonPath("$.data.totalDebitAmount").value("101.51"))
                .andExpect(jsonPath("$.data.appliedRate").value("198.69"))
                .andExpect(jsonPath("$.data.rateUnit").value(1))
                .andExpect(jsonPath("$.data.rateDate").value("2026-10-28"))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-29T03:05:00Z"));

        verify(quoteService).issue(
                new IssueQuoteCommand(USER_ID, Currency.CNY, Currency.KRW, new BigDecimal("100.50")));
    }

    @Test
    void acceptsAmountAsJsonNumber() throws Exception {
        when(quoteService.issue(any())).thenReturn(new ExchangeQuoteView(yuanToWon(), false));

        mockMvc.perform(post("/api/v1/exchange-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":100000}
                                """))
                .andExpect(status().isCreated());

        verify(quoteService).issue(
                new IssueQuoteCommand(USER_ID, Currency.KRW, Currency.JPY, new BigDecimal("100000")));
    }

    @Test
    void rejectsUnsupportedCurrencyCode() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"USD","amount":"100000"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_UNSUPPORTED_CURRENCY"));

        verifyNoInteractions(quoteService);
    }

    @Test
    void rejectsMissingFields() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.length()").value(2));

        verifyNoInteractions(quoteService);
    }

    @Test
    void rejectsRequestWithoutCurrentUser() throws Exception {
        currentUser = Optional.empty();

        mockMvc.perform(post("/api/v1/exchange-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(quoteService);
    }

    @Test
    void returnsServiceUnavailableWhenRateCannotBeUsed() throws Exception {
        when(quoteService.issue(any())).thenThrow(new BusinessException(ExchangeQuoteErrorCode.RATE_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/exchange-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_RATE_UNAVAILABLE"));
    }

    @Test
    void showsExpiredQuoteAsExpired() throws Exception {
        when(quoteService.find(USER_ID, QUOTE_ID)).thenReturn(new ExchangeQuoteView(yuanToWon(), true));

        mockMvc.perform(get("/api/v1/exchange-quotes/{quoteId}", QUOTE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quoteId").value(QUOTE_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));
    }

    @Test
    void returnsNotFoundForOthersQuote() throws Exception {
        when(quoteService.find(USER_ID, QUOTE_ID))
                .thenThrow(new BusinessException(ExchangeQuoteErrorCode.QUOTE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/exchange-quotes/{quoteId}", QUOTE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_NOT_FOUND"));
    }

    @Test
    void rejectsMalformedQuoteId() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-quotes/{quoteId}", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(quoteService);
    }

    private static ExchangeQuote yuanToWon() {
        ExchangeQuote quote = ExchangeQuote.issue(USER_ID, QuoteType.USER,
                new QuoteCurrencies(Currency.CNY, Currency.KRW), new Money(Currency.CNY, 10050),
                QuoteFixtures.savedRate(ForeignCurrency.CNY, "198.69000000"), QuoteFixtures.policy(), NOW);
        ReflectionTestUtils.setField(quote, "id", QUOTE_ID);
        return quote;
    }
}

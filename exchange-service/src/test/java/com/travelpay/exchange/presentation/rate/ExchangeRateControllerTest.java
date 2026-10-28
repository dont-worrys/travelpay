package com.travelpay.exchange.presentation.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExchangeRateControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);
    private static final Instant COLLECTED_AT = Instant.parse("2026-10-28T02:10:00Z");

    private final ExchangeRateQueryService queryService = mock(ExchangeRateQueryService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ExchangeRateController(queryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void returnsLatestRatesOfAllCurrencies() throws Exception {
        when(queryService.findLatest(Optional.empty())).thenReturn(List.of(jpy(), cny()));

        mockMvc.perform(get("/api/v1/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].currency").value("JPY"))
                .andExpect(jsonPath("$.data[0].priceCurrency").value("KRW"))
                .andExpect(jsonPath("$.data[0].unit").value(100))
                .andExpect(jsonPath("$.data[0].midRate").value("838.3"))
                .andExpect(jsonPath("$.data[0].buyRate").value("846.68"))
                .andExpect(jsonPath("$.data[0].sellRate").value("829.91"))
                .andExpect(jsonPath("$.data[0].source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.data[0].rateDate").value("2026-10-28"))
                .andExpect(jsonPath("$.data[0].collectedAt").value("2026-10-28T02:10:00Z"))
                .andExpect(jsonPath("$.data[1].currency").value("CNY"))
                .andExpect(jsonPath("$.data[1].unit").value(1));
    }

    @Test
    void returnsListEvenWhenFilteredToOneCurrency() throws Exception {
        when(queryService.findLatest(Optional.of(ForeignCurrency.JPY))).thenReturn(List.of(jpy()));

        mockMvc.perform(get("/api/v1/exchange-rates").param("currency", "JPY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].currency").value("JPY"));
    }

    @Test
    void acceptsLowercaseCurrencyCode() throws Exception {
        when(queryService.findLatest(Optional.of(ForeignCurrency.CNY))).thenReturn(List.of(cny()));

        mockMvc.perform(get("/api/v1/exchange-rates").param("currency", "cny"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].currency").value("CNY"));

        verify(queryService).findLatest(Optional.of(ForeignCurrency.CNY));
    }

    @Test
    void rejectsUnsupportedCurrencyWithBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates").param("currency", "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_UNSUPPORTED_CURRENCY"))
                .andExpect(jsonPath("$.error.message").value("지원하지 않는 통화입니다."));

        verifyNoInteractions(queryService);
    }

    @Test
    void returnsEmptyListWhenNothingIsCollectedYet() throws Exception {
        when(queryService.findLatest(Optional.empty())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private static ExchangeRate jpy() {
        // DB에서 읽은 값처럼 NUMERIC(18,8) 자릿수를 가진 값
        return ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100,
                new BigDecimal("838.30000000"), new BigDecimal("846.68000000"), new BigDecimal("829.91000000"),
                DATE, RateSource.KOREAEXIM), COLLECTED_AT);
    }

    private static ExchangeRate cny() {
        return ExchangeRate.collected(new CollectedRate(ForeignCurrency.CNY, 1,
                new BigDecimal("198.69"), new BigDecimal("200.67"), new BigDecimal("196.7"),
                DATE, RateSource.KOREAEXIM), COLLECTED_AT);
    }
}

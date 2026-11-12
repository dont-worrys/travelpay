package com.travelpay.exchange.presentation.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.exchange.application.rate.ExchangeRateChangeQueryService;
import com.travelpay.exchange.application.rate.LatestRateWithChange;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateChange;
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

    private final ExchangeRateChangeQueryService queryService = mock(ExchangeRateChangeQueryService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ExchangeRateController(queryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void returnsLatestRatesOfAllCurrencies() throws Exception {
        when(queryService.findLatestWithChange(List.of(ForeignCurrency.JPY, ForeignCurrency.CNY))).thenReturn(List.of(jpy(), cny()));

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
        when(queryService.findLatestWithChange(List.of(ForeignCurrency.JPY))).thenReturn(List.of(jpy()));

        mockMvc.perform(get("/api/v1/exchange-rates").param("currency", "JPY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].currency").value("JPY"));
    }

    @Test
    void acceptsLowercaseCurrencyCode() throws Exception {
        when(queryService.findLatestWithChange(List.of(ForeignCurrency.CNY))).thenReturn(List.of(cny()));

        mockMvc.perform(get("/api/v1/exchange-rates").param("currency", "cny"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].currency").value("CNY"));

        verify(queryService).findLatestWithChange(List.of(ForeignCurrency.CNY));
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
        when(queryService.findLatestWithChange(List.of(ForeignCurrency.JPY, ForeignCurrency.CNY))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void rejectsBlankCurrencyInsteadOfReturningAllCurrencies() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates").param("currency", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_UNSUPPORTED_CURRENCY"));

        verifyNoInteractions(queryService);
    }

    /** 전일 대비(#272): 전일 매매기준율·고시일, 변동 값, 변동률을 문자열로 내려줍니다. 내림은 음수입니다. */
    @Test
    void includesChangeAgainstPreviousRateDate() throws Exception {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        RateChange rise = new RateChange(new BigDecimal("836.20000000"), friday, new BigDecimal("2.10000000"),
                new BigDecimal("0.25"));
        RateChange fall = new RateChange(new BigDecimal("200.00"), friday, new BigDecimal("-1.31"),
                new BigDecimal("-0.66"));
        when(queryService.findLatestWithChange(List.of(ForeignCurrency.JPY, ForeignCurrency.CNY))).thenReturn(List.of(
                new LatestRateWithChange(jpyRate(), rise), new LatestRateWithChange(cnyRate(), fall)));

        mockMvc.perform(get("/api/v1/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].previousMidRate").value("836.2"))
                .andExpect(jsonPath("$.data[0].previousRateDate").value("2026-10-23"))
                .andExpect(jsonPath("$.data[0].changeAmount").value("2.1"))
                .andExpect(jsonPath("$.data[0].changeRate").value("0.25"))
                .andExpect(jsonPath("$.data[1].changeAmount").value("-1.31"))
                .andExpect(jsonPath("$.data[1].changeRate").value("-0.66"));
    }

    /** 전일 환율이 없으면(수집 첫날) 전일 대비 필드는 모두 null이고 나머지는 그대로입니다. */
    @Test
    void changeFieldsAreNullWithoutPreviousRate() throws Exception {
        when(queryService.findLatestWithChange(List.of(ForeignCurrency.JPY))).thenReturn(List.of(jpy()));

        mockMvc.perform(get("/api/v1/exchange-rates").param("currency", "JPY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].midRate").value("838.3"))
                .andExpect(jsonPath("$.data[0].previousMidRate").isEmpty())
                .andExpect(jsonPath("$.data[0].previousRateDate").isEmpty())
                .andExpect(jsonPath("$.data[0].changeAmount").isEmpty())
                .andExpect(jsonPath("$.data[0].changeRate").isEmpty());
    }

    private static LatestRateWithChange jpy() {
        return new LatestRateWithChange(jpyRate(), null);
    }

    private static ExchangeRate jpyRate() {
        // DB에서 읽은 값처럼 NUMERIC(18,8) 자릿수를 가진 값
        return ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100,
                new BigDecimal("838.30000000"), new BigDecimal("846.68000000"), new BigDecimal("829.91000000"),
                DATE, RateSource.KOREAEXIM), COLLECTED_AT);
    }

    private static LatestRateWithChange cny() {
        return new LatestRateWithChange(cnyRate(), null);
    }

    private static ExchangeRate cnyRate() {
        return ExchangeRate.collected(new CollectedRate(ForeignCurrency.CNY, 1,
                new BigDecimal("198.69"), new BigDecimal("200.67"), new BigDecimal("196.7"),
                DATE, RateSource.KOREAEXIM), COLLECTED_AT);
    }
}

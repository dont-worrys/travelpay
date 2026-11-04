package com.travelpay.exchange.application.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeRateQueryServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);

    private final ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
    private final ExchangeRateProvider provider = mock(ExchangeRateProvider.class);

    /** 여러 통화는 현재 모드 출처로 쿼리 하나에 맡깁니다. 통화별 최신 고르기·빠지는 통화는 저장소 통합 테스트가 확인합니다. */
    @Test
    void readsAllRequestedCurrenciesInOneQueryFromActiveSource() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        ExchangeRate cny = rate(ForeignCurrency.CNY);
        List<ForeignCurrency> all = List.of(ForeignCurrency.values());
        when(repository.findLatestOfEach(RateSource.KOREAEXIM, all)).thenReturn(List.of(jpy, cny));

        assertEquals(List.of(jpy, cny), service.findLatest(all));
        verify(repository, never()).findLatest(any(), any());
    }

    @Test
    void passesOnlyRequestedCurrency() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate cny = rate(ForeignCurrency.CNY);
        when(repository.findLatestOfEach(RateSource.KOREAEXIM, List.of(ForeignCurrency.CNY))).thenReturn(List.of(cny));

        assertEquals(List.of(cny), service.findLatest(List.of(ForeignCurrency.CNY)));
    }

    @Test
    void findsLatestRateOfOneCurrency() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.JPY)).thenReturn(Optional.of(jpy));
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.CNY)).thenReturn(Optional.empty());

        assertEquals(Optional.of(jpy), service.findLatest(ForeignCurrency.JPY));
        assertEquals(Optional.empty(), service.findLatest(ForeignCurrency.CNY));
    }

    @Test
    void readsOnlyFromCurrentModeSourceAndNeverCallsExternalApi() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        List<ForeignCurrency> all = List.of(ForeignCurrency.values());
        when(repository.findLatestOfEach(any(), any())).thenReturn(List.of());

        service.findLatest(all);

        verify(repository).findLatestOfEach(RateSource.MOCK, all);
        verify(repository, never()).findLatestOfEach(RateSource.KOREAEXIM, all);
        verify(provider, never()).fetch(any());
    }

    private ExchangeRateQueryService serviceWithSource(RateSource source) {
        when(provider.source()).thenReturn(source);
        return new ExchangeRateQueryService(repository, provider);
    }

    private static ExchangeRate rate(ForeignCurrency currency) {
        return ExchangeRate.collected(new CollectedRate(currency, currency == ForeignCurrency.JPY ? 100 : 1,
                new BigDecimal("838.3"), new BigDecimal("846.68"), new BigDecimal("829.91"),
                DATE, RateSource.KOREAEXIM), Instant.parse("2026-10-28T02:10:00Z"));
    }
}

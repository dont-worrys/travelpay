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

    @Test
    void returnsLatestRateOfEachSupportedCurrencyFromActiveSource() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        ExchangeRate cny = rate(ForeignCurrency.CNY);
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.JPY)).thenReturn(Optional.of(jpy));
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.CNY)).thenReturn(Optional.of(cny));

        assertEquals(List.of(jpy, cny), service.findLatest(List.of(ForeignCurrency.values())));
    }

    @Test
    void returnsOnlyRequestedCurrency() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate cny = rate(ForeignCurrency.CNY);
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.CNY)).thenReturn(Optional.of(cny));

        assertEquals(List.of(cny), service.findLatest(List.of(ForeignCurrency.CNY)));
        verify(repository, never()).findLatest(RateSource.KOREAEXIM, ForeignCurrency.JPY);
    }

    @Test
    void omitsCurrencyThatHasNoCollectedRateYet() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.JPY)).thenReturn(Optional.of(jpy));
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.CNY)).thenReturn(Optional.empty());

        assertEquals(List.of(jpy), service.findLatest(List.of(ForeignCurrency.values())));
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
        when(repository.findLatest(any(), any())).thenReturn(Optional.empty());

        service.findLatest(List.of(ForeignCurrency.values()));

        verify(repository).findLatest(RateSource.MOCK, ForeignCurrency.JPY);
        verify(repository, never()).findLatest(RateSource.KOREAEXIM, ForeignCurrency.JPY);
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

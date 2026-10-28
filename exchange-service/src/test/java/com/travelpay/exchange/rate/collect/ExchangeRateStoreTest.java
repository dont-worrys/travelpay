package com.travelpay.exchange.rate.collect;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.travelpay.exchange.rate.domain.ExchangeRate;
import com.travelpay.exchange.rate.domain.ExchangeRateRepository;
import com.travelpay.exchange.rate.domain.ForeignCurrency;
import com.travelpay.exchange.rate.domain.RateSource;
import com.travelpay.exchange.rate.policy.RateCollectionPolicy;
import com.travelpay.exchange.rate.provider.CollectedRate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeRateStoreTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    private final ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
    private final RateCollectionPolicy policy = mock(RateCollectionPolicy.class);
    private final ExchangeRateStore store = new ExchangeRateStore(repository, policy);

    @BeforeEach
    void setUp() {
        when(repository.save(any(ExchangeRate.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void savesCollectedRateWithCollectionTime() {
        when(policy.publishesOncePerDay()).thenReturn(true);

        store.saveNew(List.of(rate(ForeignCurrency.JPY, DATE)), NOW);

        ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);
        verify(repository).save(captor.capture());
        ExchangeRate saved = captor.getValue();
        assertEquals(ForeignCurrency.JPY, saved.getCurrency());
        assertEquals("KRW", saved.getPriceCurrency());
        assertEquals(100, saved.getUnit());
        assertEquals(new BigDecimal("838.3"), saved.getMidRate());
        assertEquals(new BigDecimal("846.68"), saved.getBuyRate());
        assertEquals(new BigDecimal("829.91"), saved.getSellRate());
        assertEquals(RateSource.KOREAEXIM, saved.getSource());
        assertEquals(DATE, saved.getRateDate());
        assertEquals(NOW, saved.getCollectedAt());
    }

    @Test
    void skipsRatesAlreadyStoredForSameDayWhenPublishedOncePerDay() {
        when(policy.publishesOncePerDay()).thenReturn(true);
        when(repository.existsBySourceAndCurrencyAndRateDate(RateSource.KOREAEXIM, ForeignCurrency.JPY, DATE))
                .thenReturn(true);

        List<ExchangeRate> saved = store.saveNew(
                List.of(rate(ForeignCurrency.JPY, DATE), rate(ForeignCurrency.CNY, DATE)), NOW);

        assertEquals(List.of(ForeignCurrency.CNY), saved.stream().map(ExchangeRate::getCurrency).toList());
    }

    @Test
    void savesEveryCollectionWhenSourcePublishesManyTimesPerDay() {
        when(policy.publishesOncePerDay()).thenReturn(false);

        List<ExchangeRate> saved = store.saveNew(List.of(rate(ForeignCurrency.JPY, DATE)), NOW);

        assertEquals(1, saved.size());
        verify(repository, never()).existsBySourceAndCurrencyAndRateDate(any(), any(), any());
    }

    @Test
    void latestRateDateIsEarliestAmongSupportedCurrencies() {
        stubLatest(ForeignCurrency.JPY, Optional.of(DATE));
        stubLatest(ForeignCurrency.CNY, Optional.of(DATE.minusDays(1)));

        assertEquals(Optional.of(DATE.minusDays(1)), store.latestRateDate(RateSource.KOREAEXIM));
    }

    @Test
    void latestRateDateIsEmptyWhenAnyCurrencyIsMissing() {
        stubLatest(ForeignCurrency.JPY, Optional.of(DATE));
        stubLatest(ForeignCurrency.CNY, Optional.empty());

        assertEquals(Optional.empty(), store.latestRateDate(RateSource.KOREAEXIM));
    }

    private void stubLatest(ForeignCurrency currency, Optional<LocalDate> rateDate) {
        when(repository.findFirstBySourceAndCurrencyOrderByRateDateDescCollectedAtDesc(RateSource.KOREAEXIM, currency))
                .thenReturn(rateDate.map(date -> ExchangeRate.collected(rate(currency, date), NOW)));
    }

    private static CollectedRate rate(ForeignCurrency currency, LocalDate date) {
        return new CollectedRate(currency, currency == ForeignCurrency.JPY ? 100 : 1,
                new BigDecimal("838.3"), new BigDecimal("846.68"), new BigDecimal("829.91"),
                date, RateSource.KOREAEXIM);
    }
}

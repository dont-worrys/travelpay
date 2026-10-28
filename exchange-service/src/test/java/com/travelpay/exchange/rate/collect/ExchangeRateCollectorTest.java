package com.travelpay.exchange.rate.collect;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.travelpay.exchange.rate.domain.ForeignCurrency;
import com.travelpay.exchange.rate.domain.RateSource;
import com.travelpay.exchange.rate.policy.RateCollectionPolicy;
import com.travelpay.exchange.rate.provider.CollectedRate;
import com.travelpay.exchange.rate.provider.ExchangeRateFetchException;
import com.travelpay.exchange.rate.provider.ExchangeRateProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeRateCollectorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z"); // 서울 12:00
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
    private final RateCollectionPolicy policy = mock(RateCollectionPolicy.class);
    private final ExchangeRateStore store = mock(ExchangeRateStore.class);
    private final ExchangeRateCollector collector =
            new ExchangeRateCollector(provider, policy, store, Clock.fixed(NOW, SEOUL));

    @BeforeEach
    void setUp() {
        when(provider.source()).thenReturn(RateSource.KOREAEXIM);
    }

    @Test
    void savesFetchedRatesWhenPolicyAllows() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.of(TODAY.minusDays(1)));
        when(policy.shouldCollect(TODAY, Optional.of(TODAY.minusDays(1)))).thenReturn(true);
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);

        collector.collectIfNeeded();

        verify(store).saveNew(rates, NOW);
    }

    @Test
    void skipsFetchingWhenPolicyRefuses() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.of(TODAY));
        when(policy.shouldCollect(TODAY, Optional.of(TODAY))).thenReturn(false);

        collector.collectIfNeeded();

        verify(provider, never()).fetch(any());
        verify(store, never()).saveNew(anyList(), any());
    }

    @Test
    void doesNotSaveWhenRatesAreNotPublishedYet() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(policy.shouldCollect(TODAY, Optional.empty())).thenReturn(true);
        when(provider.fetch(TODAY)).thenReturn(List.of());

        collector.collectIfNeeded();

        verify(store, never()).saveNew(anyList(), any());
    }

    @Test
    void swallowsFetchFailureSoNextTriggerCanRetry() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(policy.shouldCollect(TODAY, Optional.empty())).thenReturn(true);
        when(provider.fetch(TODAY)).thenThrow(new ExchangeRateFetchException("down"));

        assertDoesNotThrow(collector::collectIfNeeded);
        verify(store, never()).saveNew(anyList(), any());
    }

    @Test
    void treatsUniqueViolationFromConcurrentCollectionAsAlreadySaved() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(policy.shouldCollect(TODAY, Optional.empty())).thenReturn(true);
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        when(store.saveNew(rates, NOW)).thenThrow(new DataIntegrityViolationException("uk_exchange_rates_koreaexim_daily"));

        assertDoesNotThrow(collector::collectIfNeeded);
    }

    @Test
    void startupWalksBackToMostRecentPublishedDateWhenNothingIsStored() {
        // 일요일·토요일은 비어 있고 금요일 고시분이 있는 상황
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(provider.fetch(TODAY)).thenReturn(List.of());
        when(provider.fetch(TODAY.minusDays(1))).thenReturn(List.of());
        List<CollectedRate> friday = List.of(rate(TODAY.minusDays(2)));
        when(provider.fetch(TODAY.minusDays(2))).thenReturn(friday);

        collector.collectLatestIfEmpty();

        verify(store).saveNew(friday, NOW);
        verify(provider, never()).fetch(TODAY.minusDays(3));
    }

    @Test
    void startupDoesNothingWhenRatesAreAlreadyStored() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.of(TODAY.minusDays(3)));

        collector.collectLatestIfEmpty();

        verify(provider, never()).fetch(any());
    }

    @Test
    void startupStopsAtFirstFetchFailure() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(provider.fetch(TODAY)).thenThrow(new ExchangeRateFetchException("auth error"));

        collector.collectLatestIfEmpty();

        verify(provider, times(1)).fetch(any());
        verify(store, never()).saveNew(anyList(), any());
    }

    @Test
    void startupGivesUpAfterLookbackDays() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(provider.fetch(any())).thenReturn(List.of());

        collector.collectLatestIfEmpty();

        verify(provider, times(ExchangeRateCollector.INITIAL_LOOKBACK_DAYS)).fetch(any());
        verify(store, never()).saveNew(anyList(), any());
    }

    private static CollectedRate rate(LocalDate date) {
        return new CollectedRate(ForeignCurrency.JPY, 100,
                new BigDecimal("838.3"), new BigDecimal("846.68"), new BigDecimal("829.91"),
                date, RateSource.KOREAEXIM);
    }
}

package com.travelpay.exchange.application.rate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.DuplicateExchangeRateException;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateFetchException;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private final LatestRateCache cache = mock(LatestRateCache.class);
    private final ExchangeRateCollector collector =
            new ExchangeRateCollector(provider, policy, store, cache,
                    new CacheFailureLog(Clock.fixed(NOW, SEOUL)), Clock.fixed(NOW, SEOUL));

    @BeforeEach
    void setUp() {
        when(provider.source()).thenReturn(RateSource.KOREAEXIM);
    }

    @Test
    void savesFetchedRatesWhenPolicyAllows() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.of(TODAY.minusDays(1)));
        when(policy.shouldCollect(TODAY, TODAY.minusDays(1))).thenReturn(true);
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);

        collector.collectIfNeeded();

        verify(store).saveNew(rates, NOW);
    }

    @Test
    void skipsFetchingWhenPolicyRefuses() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.of(TODAY));
        when(policy.shouldCollect(TODAY, TODAY)).thenReturn(false);

        collector.collectIfNeeded();

        verify(provider, never()).fetch(any());
        verify(store, never()).saveNew(anyList(), any());
    }

    @Test
    void collectsWithoutAskingPolicyWhenNothingIsStored() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);

        collector.collectIfNeeded();

        verify(policy, never()).shouldCollect(any(), any());
        verify(store).saveNew(rates, NOW);
    }

    @Test
    void doesNotSaveWhenRatesAreNotPublishedYet() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(provider.fetch(TODAY)).thenReturn(List.of());

        collector.collectIfNeeded();

        verify(store, never()).saveNew(anyList(), any());
    }

    @Test
    void swallowsFetchFailureSoNextTriggerCanRetry() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        when(provider.fetch(TODAY)).thenThrow(new ExchangeRateFetchException("down"));

        assertDoesNotThrow(collector::collectIfNeeded);
        verify(store, never()).saveNew(anyList(), any());
    }

    @Test
    void retriesOnceInNewTransactionAfterConcurrentDuplicate() {
        // 다른 인스턴스가 JPY만 저장해, 이번 트랜잭션이 롤백되며 CNY까지 빠진 상황
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        when(store.saveNew(rates, NOW))
                .thenThrow(new DuplicateExchangeRateException("already saved"))
                .thenReturn(List.of());

        assertDoesNotThrow(collector::collectIfNeeded);
        verify(store, times(2)).saveNew(rates, NOW);
    }

    @Test
    void treatsRepeatedDuplicateAsAlreadySaved() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        when(store.saveNew(rates, NOW)).thenThrow(new DuplicateExchangeRateException("already saved"));

        assertDoesNotThrow(collector::collectIfNeeded);
        verify(store, times(2)).saveNew(rates, NOW);
    }

    @Test
    void doesNotHideOtherIntegrityViolationsAsConcurrentCollection() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        when(store.saveNew(rates, NOW)).thenThrow(new DataIntegrityViolationException("numeric field overflow"));

        assertThrows(DataIntegrityViolationException.class, collector::collectIfNeeded);
    }

    @Test
    void startupWalksBackToMostRecentPublishedDateWhenNothingIsStored() {
        // 일요일·토요일은 비어 있고 금요일 고시분이 있는 상황
        when(store.latestRateDate(RateSource.KOREAEXIM))
                .thenReturn(Optional.empty(), Optional.of(TODAY.minusDays(2)));
        when(provider.fetch(TODAY)).thenReturn(List.of());
        when(provider.fetch(TODAY.minusDays(1))).thenReturn(List.of());
        List<CollectedRate> friday = List.of(rate(TODAY.minusDays(2)), cny(TODAY.minusDays(2)));
        when(provider.fetch(TODAY.minusDays(2))).thenReturn(friday);

        collector.collectLatestIfEmpty();

        verify(store).saveNew(friday, NOW);
        verify(provider, never()).fetch(TODAY.minusDays(3));
    }

    @Test
    void startupKeepsWalkingBackUntilEveryCurrencyIsStored() {
        // 오늘 고시분에 JPY만 있어, 어제 고시분에서 CNY를 채우는 상황
        when(store.latestRateDate(RateSource.KOREAEXIM))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(TODAY.minusDays(1)));
        List<CollectedRate> jpyOnly = List.of(rate(TODAY));
        List<CollectedRate> yesterday = List.of(rate(TODAY.minusDays(1)), cny(TODAY.minusDays(1)));
        when(provider.fetch(TODAY)).thenReturn(jpyOnly);
        when(provider.fetch(TODAY.minusDays(1))).thenReturn(yesterday);

        collector.collectLatestIfEmpty();

        verify(store).saveNew(jpyOnly, NOW);
        verify(store).saveNew(yesterday, NOW);
        verify(provider, never()).fetch(TODAY.minusDays(2));
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

    /** 저장(자기 트랜잭션)이 끝나 커밋된 뒤에, 새로 저장된 환율만 캐시에 넣습니다. */
    @Test
    void putsSavedRatesIntoCacheAfterStoreCommits() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY), cny(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        List<ExchangeRate> saved = List.of(ExchangeRate.collected(rate(TODAY), NOW), ExchangeRate.collected(cny(TODAY), NOW));
        when(store.saveNew(rates, NOW)).thenReturn(saved);

        collector.collectIfNeeded();

        InOrder order = inOrder(store, cache);
        order.verify(store).saveNew(rates, NOW);
        order.verify(cache).putAll(saved);
    }

    /** 저장이 실패(롤백)하면 캐시에 넣지 않습니다. DB에 없는 환율이 캐시로 나가지 않게 합니다. */
    @Test
    void doesNotTouchCacheWhenSaveFails() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        when(store.saveNew(rates, NOW)).thenThrow(new DataIntegrityViolationException("numeric field overflow"));

        assertThrows(DataIntegrityViolationException.class, collector::collectIfNeeded);
        verify(cache, never()).putAll(anyList());
    }

    /** 캐시 갱신이 실패해도 수집은 성공입니다. DB는 이미 저장됐고, 캐시의 이전 값은 유효 시간이 지나면 사라집니다. */
    @Test
    void keepsCollectionSuccessfulWhenCacheFails() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        when(store.saveNew(rates, NOW)).thenReturn(List.of(ExchangeRate.collected(rate(TODAY), NOW)));
        doThrow(new IllegalStateException("redis down")).when(cache).putAll(anyList());

        assertDoesNotThrow(collector::collectIfNeeded);
        verify(cache).putAll(anyList());
    }

    /** 이미 저장돼 건너뛴 경우(공식 환율 같은 날 재수집)처럼 새로 저장한 것이 없으면 캐시에 쓰지 않습니다. */
    @Test
    void doesNotWriteCacheWhenNothingNewIsSaved() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        when(store.saveNew(rates, NOW)).thenReturn(List.of());

        collector.collectIfNeeded();

        verify(cache, never()).putAll(anyList());
    }

    /** 중복으로 첫 저장이 롤백되면, 다시 저장해 커밋된 것만 캐시에 넣습니다. */
    @Test
    void putsOnlyRatesCommittedByRetry() {
        when(store.latestRateDate(RateSource.KOREAEXIM)).thenReturn(Optional.empty());
        List<CollectedRate> rates = List.of(rate(TODAY), cny(TODAY));
        when(provider.fetch(TODAY)).thenReturn(rates);
        List<ExchangeRate> retried = List.of(ExchangeRate.collected(cny(TODAY), NOW));
        when(store.saveNew(rates, NOW))
                .thenThrow(new DuplicateExchangeRateException("already saved"))
                .thenReturn(retried);

        collector.collectIfNeeded();

        verify(cache, times(1)).putAll(anyList());
        verify(cache).putAll(retried);
    }

    /**
     * 수집기 메서드에 트랜잭션이 걸리면 store.saveNew가 그 트랜잭션에 합류해,
     * 캐시를 넣는 시점이 커밋 전이 됩니다. 그래서 수집기에는 @Transactional을 두지 않습니다.
     */
    @Test
    void collectorItselfIsNotTransactional() {
        assertFalse(ExchangeRateCollector.class.isAnnotationPresent(Transactional.class));
        for (Method method : ExchangeRateCollector.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Transactional.class), method.getName());
        }
    }

    private static CollectedRate rate(LocalDate date) {
        return new CollectedRate(ForeignCurrency.JPY, 100,
                new BigDecimal("838.3"), new BigDecimal("846.68"), new BigDecimal("829.91"),
                date, RateSource.KOREAEXIM);
    }

    private static CollectedRate cny(LocalDate date) {
        return new CollectedRate(ForeignCurrency.CNY, 1,
                new BigDecimal("198.69"), new BigDecimal("200.67"), new BigDecimal("196.7"),
                date, RateSource.KOREAEXIM);
    }
}

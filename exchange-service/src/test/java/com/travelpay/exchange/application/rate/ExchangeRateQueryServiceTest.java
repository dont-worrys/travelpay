package com.travelpay.exchange.application.rate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
    private static final List<ForeignCurrency> ALL = List.of(ForeignCurrency.JPY, ForeignCurrency.CNY);

    private final ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
    private final ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
    private final LatestRateCache cache = mock(LatestRateCache.class);

    /** 모든 통화가 캐시에 있으면 DB를 읽지 않습니다. */
    @Test
    void readsOnlyCacheWhenEveryCurrencyIsCached() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        ExchangeRate cny = rate(ForeignCurrency.CNY);
        when(cache.findAll(RateSource.MOCK, ALL)).thenReturn(Map.of(ForeignCurrency.JPY, jpy, ForeignCurrency.CNY, cny));

        assertEquals(List.of(jpy, cny), service.findLatest(ALL));
        verify(repository, never()).findLatestOfEach(any(), any());
    }

    /** 캐시에 없는 통화만 DB 쿼리 한 번으로 읽고, 요청한 통화 순서대로 합칩니다. */
    @Test
    void readsOnlyMissingCurrenciesFromDbAndKeepsRequestedOrder() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        ExchangeRate jpyFromDb = rate(ForeignCurrency.JPY);
        ExchangeRate cnyFromCache = rate(ForeignCurrency.CNY);
        when(cache.findAll(RateSource.MOCK, ALL)).thenReturn(Map.of(ForeignCurrency.CNY, cnyFromCache));
        when(repository.findLatestOfEach(RateSource.MOCK, List.of(ForeignCurrency.JPY))).thenReturn(List.of(jpyFromDb));

        assertEquals(List.of(jpyFromDb, cnyFromCache), service.findLatest(ALL));
        verify(repository).findLatestOfEach(RateSource.MOCK, List.of(ForeignCurrency.JPY));
    }

    /** 캐시가 비어 있으면 요청한 통화 전부를 DB 쿼리 한 번으로 읽습니다. */
    @Test
    void readsAllFromDbWhenCacheIsEmpty() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        ExchangeRate cny = rate(ForeignCurrency.CNY);
        when(cache.findAll(RateSource.KOREAEXIM, ALL)).thenReturn(Map.of());
        when(repository.findLatestOfEach(RateSource.KOREAEXIM, ALL)).thenReturn(List.of(jpy, cny));

        assertEquals(List.of(jpy, cny), service.findLatest(ALL));
    }

    /** 캐시에도 DB에도 없는(아직 수집 안 된) 통화는 목록에서 빠집니다. */
    @Test
    void leavesOutCurrencyCollectedNowhere() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        when(cache.findAll(RateSource.MOCK, ALL)).thenReturn(Map.of(ForeignCurrency.JPY, jpy));
        when(repository.findLatestOfEach(RateSource.MOCK, List.of(ForeignCurrency.CNY))).thenReturn(List.of());

        assertEquals(List.of(jpy), service.findLatest(ALL));
    }

    @Test
    void readsOneCurrencyFromCacheWithoutDb() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        when(cache.find(RateSource.MOCK, ForeignCurrency.JPY)).thenReturn(Optional.of(jpy));

        assertEquals(Optional.of(jpy), service.findLatest(ForeignCurrency.JPY));
        verify(repository, never()).findLatest(any(), any());
    }

    @Test
    void readsOneCurrencyFromDbOnCacheMiss() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.KOREAEXIM);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        when(cache.find(any(), any())).thenReturn(Optional.empty());
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.JPY)).thenReturn(Optional.of(jpy));
        when(repository.findLatest(RateSource.KOREAEXIM, ForeignCurrency.CNY)).thenReturn(Optional.empty());

        assertEquals(Optional.of(jpy), service.findLatest(ForeignCurrency.JPY));
        assertEquals(Optional.empty(), service.findLatest(ForeignCurrency.CNY));
    }

    /** 캐시가 실패하면(Redis 꺼짐·응답 없음) 예외 없이 요청한 통화 전부를 DB에서 읽습니다. */
    @Test
    void readsAllFromDbWhenCacheFails() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        ExchangeRate cny = rate(ForeignCurrency.CNY);
        when(cache.findAll(any(), any())).thenThrow(new IllegalStateException("redis down"));
        when(repository.findLatestOfEach(RateSource.MOCK, ALL)).thenReturn(List.of(jpy, cny));

        assertEquals(List.of(jpy, cny), service.findLatest(ALL));
    }

    /** 견적용 한 통화 조회도 캐시가 실패하면 DB에서 읽습니다. 캐시 장애로 견적 발급이 막히지 않습니다. */
    @Test
    void readsOneCurrencyFromDbWhenCacheFails() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        ExchangeRate jpy = rate(ForeignCurrency.JPY);
        when(cache.find(any(), any())).thenThrow(new IllegalStateException("redis down"));
        when(repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY)).thenReturn(Optional.of(jpy));

        assertEquals(Optional.of(jpy), service.findLatest(ForeignCurrency.JPY));
    }

    /** 캐시와 DB 모두 현재 모드의 출처만 읽고, 외부 API는 부르지 않습니다. */
    @Test
    void readsOnlyFromCurrentModeSourceAndNeverCallsExternalApi() {
        ExchangeRateQueryService service = serviceWithSource(RateSource.MOCK);
        when(cache.findAll(any(), any())).thenReturn(Map.of());
        when(repository.findLatestOfEach(any(), any())).thenReturn(List.of());

        service.findLatest(ALL);

        verify(cache).findAll(RateSource.MOCK, ALL);
        verify(repository).findLatestOfEach(RateSource.MOCK, ALL);
        verify(cache, never()).findAll(RateSource.KOREAEXIM, ALL);
        verify(repository, never()).findLatestOfEach(RateSource.KOREAEXIM, ALL);
        verify(provider, never()).fetch(any());
    }

    private ExchangeRateQueryService serviceWithSource(RateSource source) {
        when(provider.source()).thenReturn(source);
        return new ExchangeRateQueryService(repository, provider, cache,
                new CacheFailureLog(Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"), ZoneOffset.UTC)));
    }

    private static ExchangeRate rate(ForeignCurrency currency) {
        return ExchangeRate.collected(new CollectedRate(currency, currency == ForeignCurrency.JPY ? 100 : 1,
                new BigDecimal("838.3"), new BigDecimal("846.68"), new BigDecimal("829.91"),
                DATE, RateSource.KOREAEXIM), Instant.parse("2026-10-28T02:10:00Z"));
    }
}

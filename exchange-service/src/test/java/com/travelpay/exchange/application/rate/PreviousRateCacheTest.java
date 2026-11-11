package com.travelpay.exchange.application.rate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 전일 환율을 서버 메모리에 기억하는지 확인합니다(#272). DB는 가짜(Mockito)로 바꿔 몇 번 읽었는지 셉니다. */
class PreviousRateCacheTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 10, 27);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 10, 28);
    private static final LocalDate DAY_3 = LocalDate.of(2026, 10, 29);
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    private final ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
    private final Clock clock = mock(Clock.class);
    private PreviousRateCache cache;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        cache = new PreviousRateCache(repository, clock);
    }

    /** 최신 고시일이 같으면 두 번째부터는 DB를 읽지 않습니다. */
    @Test
    void readsDbOncePerLatestRateDate() {
        ExchangeRate previous = jpy("836.20", DAY_1);
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2)).thenReturn(Optional.of(previous));

        assertEquals(Optional.of(previous), cache.find(jpy("838.30", DAY_2)));
        assertEquals(Optional.of(previous), cache.find(jpy("838.40", DAY_2))); // 같은 날 다음 수집분

        verify(repository, times(1)).findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2);
    }

    /** 최신 고시일이 바뀌면(다음 날) 그 날 기준으로 다시 읽습니다. */
    @Test
    void reloadsWhenLatestRateDateChanges() {
        ExchangeRate day1 = jpy("836.20", DAY_1);
        ExchangeRate day2 = jpy("838.30", DAY_2);
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2)).thenReturn(Optional.of(day1));
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_3)).thenReturn(Optional.of(day2));

        assertEquals(Optional.of(day1), cache.find(jpy("838.30", DAY_2)));
        assertEquals(Optional.of(day2), cache.find(jpy("840.00", DAY_3)));
    }

    /** 통화마다 따로 기억합니다. */
    @Test
    void keepsEachCurrencySeparately() {
        ExchangeRate jpyPrevious = jpy("836.20", DAY_1);
        ExchangeRate cnyPrevious = cny("189.50", DAY_1);
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2)).thenReturn(Optional.of(jpyPrevious));
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.CNY, DAY_2)).thenReturn(Optional.of(cnyPrevious));

        assertEquals(Optional.of(jpyPrevious), cache.find(jpy("838.30", DAY_2)));
        assertEquals(Optional.of(cnyPrevious), cache.find(cny("190.00", DAY_2)));
        assertEquals(Optional.of(jpyPrevious), cache.find(jpy("838.30", DAY_2)));

        verify(repository, times(1)).findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2);
        verify(repository, times(1)).findLatestBefore(RateSource.MOCK, ForeignCurrency.CNY, DAY_2);
    }

    /** 전일 환율이 없으면(수집 첫날) "없음"도 기억해 같은 날 다시 읽지 않습니다. */
    @Test
    void remembersMissingPreviousRate() {
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2)).thenReturn(Optional.empty());

        assertTrue(cache.find(jpy("838.30", DAY_2)).isEmpty());
        assertTrue(cache.find(jpy("838.30", DAY_2)).isEmpty());

        verify(repository, times(1)).findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2);
    }

    /** DB에서 읽다 실패하면 전일 값 없이 돌려주고, 30초 동안은 다시 읽지 않은 뒤 그 다음에 다시 읽습니다. */
    @Test
    void waitsBeforeRetryingAfterDbFailure() {
        ExchangeRate previous = jpy("836.20", DAY_1);
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(Optional.of(previous));

        assertTrue(cache.find(jpy("838.30", DAY_2)).isEmpty());
        when(clock.instant()).thenReturn(NOW.plus(PreviousRateCache.RETRY_AFTER).minusSeconds(1));
        assertTrue(cache.find(jpy("838.30", DAY_2)).isEmpty());
        verify(repository, times(1)).findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2);

        when(clock.instant()).thenReturn(NOW.plus(PreviousRateCache.RETRY_AFTER));
        assertEquals(Optional.of(previous), cache.find(jpy("838.30", DAY_2)));
        verify(repository, times(2)).findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2);
    }

    /**
     * DB가 느리면 요청은 기다리는 시간(여기서는 50ms)만 기다리고 전일 대비 없이 응답합니다(#272 리뷰).
     * DB 읽기는 뒤에서 이어져, 끝나면 다음 요청부터 값이 나오고 DB는 다시 읽지 않습니다.
     */
    @Test
    void doesNotWaitForSlowDbBeyondWaitTime() throws Exception {
        ExchangeRate previous = jpy("836.20", DAY_1);
        CountDownLatch dbReleased = new CountDownLatch(1);
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2)).thenAnswer(invocation -> {
            dbReleased.await(5, TimeUnit.SECONDS);
            return Optional.of(previous);
        });
        PreviousRateCache slowDbCache = new PreviousRateCache(repository, clock, Duration.ofMillis(50));

        long started = System.nanoTime();
        assertTrue(slowDbCache.find(jpy("838.30", DAY_2)).isEmpty());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0, "DB를 기다리지 않음");

        dbReleased.countDown();
        assertEquals(Optional.of(previous), awaitPresent(slowDbCache, jpy("838.30", DAY_2)));
        verify(repository, times(1)).findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2);
    }

    /** 같은 칸에 요청이 몰려도 DB는 한 번만 읽고, DB가 느린 동안 요청들은 모두 제한 시간 안에 돌아옵니다. */
    @Test
    void concurrentRequestsShareOneDbReadWithoutBlocking() throws Exception {
        CountDownLatch dbReleased = new CountDownLatch(1);
        when(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2)).thenAnswer(invocation -> {
            dbReleased.await(5, TimeUnit.SECONDS);
            return Optional.of(jpy("836.20", DAY_1));
        });
        PreviousRateCache slowDbCache = new PreviousRateCache(repository, clock, Duration.ofMillis(50));

        try (ExecutorService requests = Executors.newFixedThreadPool(20)) {
            List<Future<Optional<ExchangeRate>>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(requests.submit(() -> slowDbCache.find(jpy("838.30", DAY_2))));
            }
            for (Future<Optional<ExchangeRate>> result : results) {
                assertTrue(result.get(1, TimeUnit.SECONDS).isEmpty(), "DB가 느린 동안은 전일 대비 없이 바로 응답");
            }
        } finally {
            dbReleased.countDown();
        }
        verify(repository, times(1)).findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2);
    }

    /** 뒤에서 이어지는 DB 읽기가 끝날 때까지(최대 2초) 다시 조회합니다. */
    private static Optional<ExchangeRate> awaitPresent(PreviousRateCache cache, ExchangeRate latest)
            throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            Optional<ExchangeRate> found = cache.find(latest);
            if (found.isPresent()) {
                return found;
            }
            Thread.sleep(50);
        }
        return Optional.empty();
    }

    private static ExchangeRate jpy(String mid, LocalDate rateDate) {
        return rate(ForeignCurrency.JPY, 100, mid, rateDate);
    }

    private static ExchangeRate cny(String mid, LocalDate rateDate) {
        return rate(ForeignCurrency.CNY, 1, mid, rateDate);
    }

    private static ExchangeRate rate(ForeignCurrency currency, int unit, String mid, LocalDate rateDate) {
        BigDecimal value = new BigDecimal(mid);
        return ExchangeRate.restore(UUID.randomUUID(),
                new CollectedRate(currency, unit, value, value, value, rateDate, RateSource.MOCK),
                rateDate.atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}

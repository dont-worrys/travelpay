package com.travelpay.exchange.infrastructure.external.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockRateProviderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Instant COLLECTED_AT = Instant.parse("2026-11-02T02:00:00Z");
    private static final Map<ForeignCurrency, BigDecimal> DEFAULTS = Map.of(
            ForeignCurrency.JPY, new BigDecimal("900.00"),
            ForeignCurrency.CNY, new BigDecimal("190.00"));

    private final ExchangeRateRepository repository = mock(ExchangeRateRepository.class);

    @BeforeEach
    void noRatesByDefault() {
        for (ForeignCurrency currency : ForeignCurrency.values()) {
            when(repository.findLatest(RateSource.KOREAEXIM, currency)).thenReturn(Optional.empty());
            when(repository.findLatest(RateSource.MOCK, currency)).thenReturn(Optional.empty());
        }
    }

    @Test
    void sourceIsMock() {
        assertEquals(RateSource.MOCK, provider(0).source());
    }

    /** 공식 환율이 있으면 그 매매기준율을 기준값으로, 살 때·팔 때 환율은 공식 환율과 같은 비율로 */
    @Test
    void usesLatestOfficialRateAsBase() {
        official(ForeignCurrency.JPY, "838.30", "846.68", "829.92");

        CollectedRate jpy = jpy(provider(0).fetch(TODAY));

        assertEquals(new BigDecimal("838.30"), jpy.midRate());
        assertEquals(new BigDecimal("846.68"), jpy.buyRate());
        assertEquals(new BigDecimal("829.92"), jpy.sellRate());
        assertEquals(RateSource.MOCK, jpy.source());
        assertEquals(TODAY, jpy.rateDate());
        assertEquals(100, jpy.unit());
    }

    /** 가짜 환율이 있으면 그 값에서 이어서 움직인다. 기준값은 여전히 공식 환율 */
    @Test
    void continuesFromLatestMockRate() {
        official(ForeignCurrency.JPY, "838.30", "846.68", "829.92");
        when(repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY))
                .thenReturn(Optional.of(saved(ForeignCurrency.JPY, "840.00", "848.40", "831.60", RateSource.MOCK)));

        // 840.00에서 최대로 오름: 840.00 × 1.0005 = 840.42
        CollectedRate jpy = jpy(provider(1.0).fetch(TODAY));

        assertEquals(new BigDecimal("840.42"), jpy.midRate());
        // 살 때·팔 때 환율은 공식 환율의 비율(846.68 ÷ 838.30, 829.92 ÷ 838.30)을 새 값에 곱한다
        assertEquals(new BigDecimal("848.82"), jpy.buyRate());
        assertEquals(new BigDecimal("832.02"), jpy.sellRate());
    }

    /** 공식 환율이 없으면 설정 기본값을 기준으로 하고, 살 때·팔 때 환율은 ±1% */
    @Test
    void usesDefaultWhenNoOfficialRate() {
        CollectedRate jpy = jpy(provider(0).fetch(TODAY));

        assertEquals(new BigDecimal("900.00"), jpy.midRate());
        assertEquals(new BigDecimal("909.00"), jpy.buyRate());
        assertEquals(new BigDecimal("891.00"), jpy.sellRate());
    }

    @Test
    void returnsOneRatePerSupportedCurrency() {
        List<CollectedRate> rates = provider(0).fetch(TODAY);

        assertEquals(List.of(ForeignCurrency.values()), rates.stream().map(CollectedRate::currency).toList());
        assertEquals(new BigDecimal("190.00"), rates.get(1).midRate());
        assertEquals(1, rates.get(1).unit());
    }

    @Test
    void rejectsMissingOrNonPositiveDefaults() {
        MockRateFluctuation fluctuation = fluctuation(0);
        assertThrows(IllegalArgumentException.class, () -> new MockRateProvider(repository, fluctuation,
                Map.of(ForeignCurrency.JPY, new BigDecimal("900.00"))));
        assertThrows(IllegalArgumentException.class, () -> new MockRateProvider(repository, fluctuation,
                Map.of(ForeignCurrency.JPY, new BigDecimal("900.00"), ForeignCurrency.CNY, BigDecimal.ZERO)));
    }

    private MockRateProvider provider(double direction) {
        return new MockRateProvider(repository, fluctuation(direction), DEFAULTS);
    }

    private void official(ForeignCurrency currency, String mid, String buy, String sell) {
        when(repository.findLatest(RateSource.KOREAEXIM, currency))
                .thenReturn(Optional.of(saved(currency, mid, buy, sell, RateSource.KOREAEXIM)));
    }

    private static ExchangeRate saved(ForeignCurrency currency, String mid, String buy, String sell,
                                      RateSource source) {
        return ExchangeRate.collected(new CollectedRate(currency, currency.quoteUnit(), new BigDecimal(mid),
                new BigDecimal(buy), new BigDecimal(sell), TODAY, source), COLLECTED_AT);
    }

    private static CollectedRate jpy(List<CollectedRate> rates) {
        return rates.stream().filter(rate -> rate.currency() == ForeignCurrency.JPY).findFirst().orElseThrow();
    }

    /** ±0.05%, ±2%에 방향이 고정된 변동 규칙 */
    private static MockRateFluctuation fluctuation(double direction) {
        return new MockRateFluctuation(new BigDecimal("0.0005"), new BigDecimal("0.02"), new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble(double origin, double bound) {
                return direction;
            }
        });
    }
}

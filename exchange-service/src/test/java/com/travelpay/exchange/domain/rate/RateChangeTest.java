package com.travelpay.exchange.domain.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateChangeTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);

    @Test
    void calculatesRiseAgainstPreviousRateDate() {
        RateChange change = RateChange.between(jpy("838.30", MONDAY), jpy("836.20", FRIDAY));

        assertEquals(0, new BigDecimal("836.20").compareTo(change.previousMidRate()));
        assertEquals(FRIDAY, change.previousRateDate());
        assertEquals(0, new BigDecimal("2.10").compareTo(change.amount()));
        // 2.10 / 836.20 × 100 = 0.2511... → 0.25
        assertEquals(new BigDecimal("0.25"), change.percent());
    }

    @Test
    void fallIsNegative() {
        RateChange change = RateChange.between(jpy("830.00", MONDAY), jpy("840.00", FRIDAY));

        assertEquals(0, new BigDecimal("-10").compareTo(change.amount()));
        // -10 / 840 × 100 = -1.1904... → -1.19
        assertEquals(new BigDecimal("-1.19"), change.percent());
    }

    /** 변동률은 소수 둘째 자리 반올림(HALF_UP)입니다. 셋째 자리가 5면 올립니다. */
    @Test
    void roundsPercentHalfUp() {
        // 0.25 / 200 × 100 = 0.125 → 0.13
        assertEquals(new BigDecimal("0.13"), RateChange.between(cny("200.25", MONDAY), cny("200.00", FRIDAY)).percent());
    }

    @Test
    void noChangeIsZero() {
        RateChange change = RateChange.between(jpy("838.30", MONDAY), jpy("838.30", FRIDAY));

        assertEquals(0, BigDecimal.ZERO.compareTo(change.amount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(change.percent()));
    }

    @Test
    void rejectsDifferentCurrencyOrSameRateDate() {
        assertThrows(IllegalArgumentException.class, () -> RateChange.between(jpy("838.30", MONDAY), cny("190", FRIDAY)));
        assertThrows(IllegalArgumentException.class, () -> RateChange.between(jpy("838.30", MONDAY), jpy("836", MONDAY)));
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
                new CollectedRate(currency, unit, value, value, value, rateDate, RateSource.KOREAEXIM),
                rateDate.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(7_800));
    }
}

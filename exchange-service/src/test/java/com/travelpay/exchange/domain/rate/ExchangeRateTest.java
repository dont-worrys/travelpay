package com.travelpay.exchange.domain.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 매매기준율 환산. 2026-10-28 실제 고시값(JPY 100엔당 838.3원, CNY 198.69원) 기준입니다. */
class ExchangeRateTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);
    private static final Instant COLLECTED_AT = Instant.parse("2026-10-28T02:10:00Z");
    private static final ExchangeRate JPY = rate(ForeignCurrency.JPY, 100, "838.3");
    private static final ExchangeRate CNY = rate(ForeignCurrency.CNY, 1, "198.69");

    @Test
    void convertsWonToYenPer100AndRoundsDown() {
        // 100,000 × 100 ÷ 838.3 = 11,928.92…
        assertEquals(new Money(Currency.JPY, 11928), JPY.convertAtMidRate(new Money(Currency.KRW, 100000)));
    }

    @Test
    void convertsYenToWon() {
        // 1,000 × 838.3 ÷ 100 = 8,383
        assertEquals(new Money(Currency.KRW, 8383), JPY.convertAtMidRate(new Money(Currency.JPY, 1000)));
    }

    @Test
    void convertsYuanWithCentsToWonAndRoundsDown() {
        // 100.50 × 198.69 = 19,968.345
        assertEquals(new Money(Currency.KRW, 19968), CNY.convertAtMidRate(new Money(Currency.CNY, 10050)));
    }

    @Test
    void convertsWonToYuanKeepingCents() {
        // 10,000 ÷ 198.69 = 50.329…
        assertEquals(new Money(Currency.CNY, 5032), CNY.convertAtMidRate(new Money(Currency.KRW, 10000)));
    }

    @Test
    void usesMidRateNotBuyOrSellRate() {
        ExchangeRate withSpread = ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100,
                new BigDecimal("838.3"), new BigDecimal("846.68"), new BigDecimal("829.91"),
                DATE, RateSource.KOREAEXIM), COLLECTED_AT);

        assertEquals(new Money(Currency.KRW, 8383), withSpread.convertAtMidRate(new Money(Currency.JPY, 1000)));
        assertEquals(new Money(Currency.JPY, 11928), withSpread.convertAtMidRate(new Money(Currency.KRW, 100000)));
    }

    @Test
    void rejectsCurrencyOfOtherRate() {
        assertThrows(IllegalArgumentException.class, () -> JPY.convertAtMidRate(new Money(Currency.CNY, 100)));
    }

    private static ExchangeRate rate(ForeignCurrency currency, int unit, String midRate) {
        BigDecimal value = new BigDecimal(midRate);
        return ExchangeRate.collected(
                new CollectedRate(currency, unit, value, value, value, DATE, RateSource.KOREAEXIM), COLLECTED_AT);
    }
}

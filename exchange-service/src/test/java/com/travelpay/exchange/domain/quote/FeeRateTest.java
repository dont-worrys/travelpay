package com.travelpay.exchange.domain.quote;

import java.math.BigDecimal;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeeRateTest {

    private static final FeeRate ONE_PERCENT = new FeeRate(new BigDecimal("0.01"));

    /** 금액·수수료는 최소 단위입니다. (CNY 10050 = 100.50위안) */
    @ParameterizedTest
    @CsvSource({
            "KRW, 100000, 1000",
            "JPY, 1000, 10",
            "CNY, 10050, 101",
            "KRW, 1050, 11",
            "JPY, 1, 1"
    })
    void chargesFeeInSendingCurrencyRoundedUp(Currency currency, long amount, long fee) {
        assertEquals(new Money(currency, fee), ONE_PERCENT.feeFor(new Money(currency, amount)));
    }

    @Test
    void zeroRateChargesNothing() {
        assertEquals(new Money(Currency.KRW, 0), new FeeRate(BigDecimal.ZERO).feeFor(new Money(Currency.KRW, 1000)));
    }

    /** 합계 = 보내는 금액 + 수수료(올림). 최소 단위입니다. (CNY 10151 = 101.51위안) */
    @ParameterizedTest
    @CsvSource({
            "KRW, 500000, 495049",
            "KRW, 101, 100",
            "KRW, 102, 100",
            "CNY, 10151, 10050",
            "JPY, 0, 0"
    })
    void findsLargestAmountWhoseTotalWithFeeFitsWithin(Currency currency, long total, long amount) {
        assertEquals(new Money(currency, amount), ONE_PERCENT.maxAmountWithin(new Money(currency, total)));
    }

    /**
     * 나눗셈 결과를 버림만 해도 보정이 필요 없는지 모든 합계로 확인합니다.
     * 결과는 수수료를 더해도 합계 이하이고, 최소 단위 하나를 더하면 합계를 넘어야 합니다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0.01", "0.015", "0.123457"})
    void maxAmountIsExactForEveryTotal(String rate) {
        FeeRate feeRate = new FeeRate(new BigDecimal(rate));
        for (long total = 0; total <= 30_000; total++) {
            Money max = feeRate.maxAmountWithin(new Money(Currency.KRW, total));
            Money oneMore = new Money(Currency.KRW, max.minorUnits() + 1);

            assertTrue(max.minorUnits() + feeRate.feeFor(max).minorUnits() <= total, "total " + total);
            assertTrue(oneMore.minorUnits() + feeRate.feeFor(oneMore).minorUnits() > total, "total " + total);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1", "1.5", "0.0000001"})
    void rejectsRateOutOfRangeOrTooPrecise(String value) {
        assertThrows(IllegalArgumentException.class, () -> new FeeRate(new BigDecimal(value)));
    }
}

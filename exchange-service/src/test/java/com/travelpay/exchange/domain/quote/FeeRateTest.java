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

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1", "1.5", "0.0000001"})
    void rejectsRateOutOfRangeOrTooPrecise(String value) {
        assertThrows(IllegalArgumentException.class, () -> new FeeRate(new BigDecimal(value)));
    }
}

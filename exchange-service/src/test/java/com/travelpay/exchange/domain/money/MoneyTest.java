package com.travelpay.exchange.domain.money;

import java.math.BigDecimal;

import com.travelpay.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({
            "KRW, 100000, 100000",
            "JPY, 1000, 1000",
            "CNY, 100.50, 10050",
            "CNY, 100.5, 10050",
            "CNY, 10, 1000",
            "KRW, 1000.00, 1000"
    })
    void storesAmountInMinorUnits(Currency currency, String amount, long minorUnits) {
        assertEquals(new Money(currency, minorUnits), Money.of(currency, new BigDecimal(amount)));
    }

    @ParameterizedTest
    @CsvSource({"KRW, 1000.5", "JPY, 100.5", "CNY, 10.123", "KRW, -1", "KRW, 9223372036854775808"})
    void rejectsAmountThatDoesNotFitCurrency(Currency currency, String amount) {
        BusinessException exception =
                assertThrows(BusinessException.class, () -> Money.of(currency, new BigDecimal(amount)));
        assertEquals(MoneyErrorCode.INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void showsAmountWithCurrencyFractionDigits() {
        assertEquals(new BigDecimal("101.51"), new Money(Currency.CNY, 10151).amount());
        assertEquals(new BigDecimal("101000"), new Money(Currency.KRW, 101000).amount());
    }

    @Test
    void addsAndComparesOnlySameCurrency() {
        Money krw = new Money(Currency.KRW, 1000);

        assertEquals(new Money(Currency.KRW, 1010), krw.plus(new Money(Currency.KRW, 10)));
        assertThrows(IllegalArgumentException.class, () -> krw.plus(new Money(Currency.JPY, 10)));
        assertThrows(IllegalArgumentException.class, () -> krw.isLessThan(new Money(Currency.JPY, 10)));
    }

    @Test
    void findsCurrencyIgnoringCase() {
        assertEquals(Currency.CNY, Currency.find(" cny ").orElseThrow());
        assertTrue(Currency.find("USD").isEmpty());
        assertTrue(Currency.find(null).isEmpty());
    }
}

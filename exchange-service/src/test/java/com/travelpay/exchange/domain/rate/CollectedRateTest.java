package com.travelpay.exchange.domain.rate;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CollectedRateTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);

    @Test
    void acceptsQuoteUnitOfEachCurrency() {
        assertDoesNotThrow(() -> rate(ForeignCurrency.JPY, 100, "838.3"));
        assertDoesNotThrow(() -> rate(ForeignCurrency.CNY, 1, "198.69"));
    }

    @ParameterizedTest
    @CsvSource({"JPY, 1", "JPY, 0", "CNY, 100", "CNY, -1"})
    void rejectsUnitThatDiffersFromQuoteUnit(ForeignCurrency currency, int unit) {
        assertThrows(IllegalArgumentException.class, () -> rate(currency, unit, "838.3"));
    }

    @ParameterizedTest
    @CsvSource({"0", "-838.3"})
    void rejectsNonPositiveRate(String value) {
        assertThrows(IllegalArgumentException.class, () -> rate(ForeignCurrency.JPY, 100, value));
    }

    private static CollectedRate rate(ForeignCurrency currency, int unit, String value) {
        BigDecimal rate = new BigDecimal(value);
        return new CollectedRate(currency, unit, rate, rate, rate, DATE, RateSource.KOREAEXIM);
    }
}

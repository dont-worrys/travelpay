package com.travelpay.exchange.domain.rate;

import com.travelpay.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForeignCurrencyTest {

    @Test
    void parsesCodeIgnoringCaseAndSurroundingSpaces() {
        assertEquals(ForeignCurrency.JPY, ForeignCurrency.fromCode("JPY"));
        assertEquals(ForeignCurrency.CNY, ForeignCurrency.fromCode(" cny "));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "USD", "KRW", "CNH", "JPY100"})
    void rejectsUnsupportedCodes(String code) {
        var exception = assertThrows(BusinessException.class, () -> ForeignCurrency.fromCode(code));

        assertEquals(ExchangeRateErrorCode.UNSUPPORTED_CURRENCY, exception.getErrorCode());
    }
}

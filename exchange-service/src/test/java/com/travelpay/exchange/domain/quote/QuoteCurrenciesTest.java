package com.travelpay.exchange.domain.quote;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuoteCurrenciesTest {

    @Test
    void foreignSideIsTheNonWonCurrency() {
        assertEquals(ForeignCurrency.JPY, new QuoteCurrencies(Currency.KRW, Currency.JPY).foreign());
        assertEquals(ForeignCurrency.CNY, new QuoteCurrencies(Currency.CNY, Currency.KRW).foreign());
    }

    @ParameterizedTest
    @CsvSource({"KRW, KRW", "JPY, JPY", "JPY, CNY", "CNY, JPY"})
    void rejectsPairWithoutExactlyOneWonSide(Currency from, Currency to) {
        BusinessException exception = assertThrows(BusinessException.class, () -> new QuoteCurrencies(from, to));
        assertEquals(ExchangeQuoteErrorCode.UNSUPPORTED_PAIR, exception.getErrorCode());
    }
}

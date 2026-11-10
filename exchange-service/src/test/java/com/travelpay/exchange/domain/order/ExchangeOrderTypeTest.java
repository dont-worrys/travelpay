package com.travelpay.exchange.domain.order;

import com.travelpay.exchange.domain.money.Currency;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExchangeOrderTypeTest {

    @Test
    void receivingWonIsReExchange() {
        assertEquals(ExchangeOrderType.RE_EXCHANGE, ExchangeOrderType.of(Currency.KRW));
    }

    @Test
    void receivingForeignCurrencyIsExchange() {
        assertEquals(ExchangeOrderType.EXCHANGE, ExchangeOrderType.of(Currency.JPY));
        assertEquals(ExchangeOrderType.EXCHANGE, ExchangeOrderType.of(Currency.CNY));
    }

    @Test
    void needsCurrency() {
        assertThrows(NullPointerException.class, () -> ExchangeOrderType.of(null));
    }
}

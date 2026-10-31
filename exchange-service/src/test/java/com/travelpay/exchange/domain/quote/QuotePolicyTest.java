package com.travelpay.exchange.domain.quote;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.money.MoneyErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.travelpay.exchange.domain.quote.QuoteFixtures.range;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuotePolicyTest {

    private final QuotePolicy policy = QuoteFixtures.policy();

    /** 금액은 최소 단위입니다. (CNY 1000 = 10.00위안) */
    @ParameterizedTest
    @CsvSource({"KRW, 1000", "KRW, 2000000", "CNY, 1000", "JPY, 200000"})
    void acceptsAmountWithinRangeInclusive(Currency currency, long amount) {
        assertDoesNotThrow(() -> policy.checkAmount(new Money(currency, amount)));
    }

    @ParameterizedTest
    @CsvSource({"KRW, 999", "KRW, 2000001", "CNY, 999", "JPY, 0"})
    void rejectsAmountOutOfRange(Currency currency, long amount) {
        BusinessException exception =
                assertThrows(BusinessException.class, () -> policy.checkAmount(new Money(currency, amount)));
        assertEquals(MoneyErrorCode.INVALID_AMOUNT, exception.getErrorCode());
    }

    /** 잔액·결과는 최소 단위입니다. 1회 최대로 자르고, 1회 최소보다 작으면 0입니다. */
    @ParameterizedTest
    @CsvSource({
            "KRW, 500000, 495049",
            "KRW, 3000000, 2000000",
            "KRW, 1010, 1000",
            "KRW, 1009, 0",
            "CNY, 10151, 10050",
            "JPY, 0, 0"
    })
    void availableAmountFitsBalanceAndRange(Currency currency, long balance, long available) {
        assertEquals(new Money(currency, available), policy.availableAmount(new Money(currency, balance)));
    }

    @Test
    void requiresRangeForEveryCurrency() {
        FeeRate fee = new FeeRate(new BigDecimal("0.01"));
        Map<Currency, AmountRange> krwOnly = Map.of(Currency.KRW, range(Currency.KRW, 1000, 2000));

        assertThrows(IllegalArgumentException.class, () -> new QuotePolicy(Duration.ofMinutes(5), fee, krwOnly));
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuotePolicy(Duration.ZERO, policy.feeRate(), policy.limits()));
    }

    @Test
    void rejectsRangeWithZeroMinOrMinAboveMax() {
        assertThrows(IllegalArgumentException.class, () -> range(Currency.KRW, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> range(Currency.KRW, 2000, 1000));
    }
}

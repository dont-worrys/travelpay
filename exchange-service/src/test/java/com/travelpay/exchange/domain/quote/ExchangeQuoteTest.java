package com.travelpay.exchange.domain.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.money.MoneyErrorCode;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.travelpay.exchange.domain.quote.QuoteFixtures.range;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 매매기준율 JPY 838.30(unit 100), CNY 198.69(unit 1), 수수료율 1% 기준입니다. (이슈 #32 완료 기준) */
class ExchangeQuoteTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    private static final ExchangeRate JPY = QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30000000");
    private static final ExchangeRate CNY = QuoteFixtures.savedRate(ForeignCurrency.CNY, "198.69");

    @Test
    void wonToYen() {
        ExchangeQuote quote = issue(Currency.KRW, Currency.JPY, "100000", JPY);

        assertEquals(new Money(Currency.KRW, 100000), quote.fromMoney());
        assertEquals(new Money(Currency.JPY, 11928), quote.toMoney());
        assertEquals(new Money(Currency.KRW, 1000), quote.feeMoney());
        assertEquals(new Money(Currency.KRW, 101000), quote.totalDebitMoney());
    }

    @Test
    void yenToWon() {
        ExchangeQuote quote = issue(Currency.JPY, Currency.KRW, "1000", JPY);

        assertEquals(new Money(Currency.KRW, 8383), quote.toMoney());
        assertEquals(new Money(Currency.JPY, 10), quote.feeMoney());
        assertEquals(new Money(Currency.JPY, 1010), quote.totalDebitMoney());
    }

    @Test
    void yuanToWon() {
        ExchangeQuote quote = issue(Currency.CNY, Currency.KRW, "100.50", CNY);

        assertEquals(new Money(Currency.KRW, 19968), quote.toMoney());
        assertEquals(new Money(Currency.CNY, 101), quote.feeMoney());
        assertEquals(new Money(Currency.CNY, 10151), quote.totalDebitMoney());
    }

    @Test
    void snapshotsCalculationBasis() {
        ExchangeQuote quote = issue(Currency.KRW, Currency.JPY, "100000", JPY);

        assertEquals(JPY.getId(), quote.getRateId());
        assertEquals(new BigDecimal("838.30000000"), quote.getAppliedRate());
        assertEquals(100, quote.getRateUnit());
        assertEquals(QuoteFixtures.RATE_DATE, quote.getRateDate());
        assertEquals(new BigDecimal("0.01"), quote.getFeeRate());
        assertEquals(USER_ID, quote.getUserId());
        assertEquals(QuoteType.USER, quote.getQuoteType());
        assertEquals(QuoteStatus.ISSUED, quote.getStatus());
        assertEquals(NOW.plusSeconds(300), quote.getExpiresAt());
    }

    @Test
    void expiresAtExpiryInstant() {
        ExchangeQuote quote = issue(Currency.KRW, Currency.JPY, "100000", JPY);

        assertFalse(quote.isExpired(quote.getExpiresAt().minusMillis(1)));
        assertTrue(quote.isExpired(quote.getExpiresAt()));
    }

    @Test
    void usedQuoteIsNotReportedAsExpired() {
        ExchangeQuote quote = issue(Currency.KRW, Currency.JPY, "100000", JPY);
        ReflectionTestUtils.setField(quote, "status", QuoteStatus.USED);

        assertFalse(quote.isExpired(quote.getExpiresAt().plusSeconds(60)));
    }

    @Test
    void rejectsAmountOutOfRange() {
        BusinessException exception =
                assertThrows(BusinessException.class, () -> issue(Currency.KRW, Currency.JPY, "999", JPY));
        assertEquals(MoneyErrorCode.INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void rejectsQuoteWhoseReceivedAmountRoundsDownToZero() {
        // 한도 설정이 느슨해도 받는 금액이 0이면 발급하지 않습니다. (1원 → 0.119엔 → 0엔)
        QuotePolicy loose = QuoteFixtures.policyWith(Map.of(Currency.KRW, range(Currency.KRW, 1, 1000)));

        BusinessException exception = assertThrows(BusinessException.class, () -> ExchangeQuote.issue(USER_ID,
                QuoteType.USER, new QuoteCurrencies(Currency.KRW, Currency.JPY), new Money(Currency.KRW, 1),
                JPY, loose, NOW));
        assertEquals(MoneyErrorCode.INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void rejectsRateOfOtherCurrency() {
        assertThrows(IllegalArgumentException.class, () -> issue(Currency.KRW, Currency.JPY, "100000", CNY));
    }

    private static ExchangeQuote issue(Currency from, Currency to, String amount, ExchangeRate rate) {
        return ExchangeQuote.issue(USER_ID, QuoteType.USER, new QuoteCurrencies(from, to),
                Money.of(from, new BigDecimal(amount)), rate, QuoteFixtures.policy(), NOW);
    }
}

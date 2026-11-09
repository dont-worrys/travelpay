package com.travelpay.exchange.domain.reservation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.money.MoneyErrorCode;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeReservationTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final ReservationPolicy POLICY = new ReservationPolicy(Duration.ofDays(30), 10);

    @Test
    void reservesWaitingWithQuoteUnitOfForeignCurrency() {
        ExchangeReservation buyYen = reserve(Currency.KRW, Currency.JPY, "100000", "830.00");
        ExchangeReservation sellYuan = reserve(Currency.CNY, Currency.KRW, "100.50", "200");

        assertEquals(ReservationStatus.WAITING, buyYen.getStatus());
        assertEquals(100, buyYen.getRateUnit());
        assertEquals(new Money(Currency.KRW, 100_000), buyYen.fromMoney());
        assertEquals(Currency.JPY, buyYen.getToCurrency());
        assertTrue(buyYen.buysForeign());
        assertNull(buyYen.getOrderId());
        assertNull(buyYen.getExecutedAt());

        assertEquals(1, sellYuan.getRateUnit());
        assertEquals(new Money(Currency.CNY, 10050), sellYuan.fromMoney());
        assertFalse(sellYuan.buysForeign());
    }

    /** 외화를 살 때는 환율이 목표 이하가 되면 체결한다 (같은 값 포함) */
    @ParameterizedTest
    @CsvSource({"829.99, true", "830.00, true", "830.01, false"})
    void buyingForeignTriggersAtOrBelowTarget(String midRate, boolean triggered) {
        ExchangeReservation reservation = reserve(Currency.KRW, Currency.JPY, "100000", "830");

        assertEquals(triggered, reservation.isTriggeredBy(QuoteFixtures.savedRate(ForeignCurrency.JPY, midRate)));
    }

    /** 외화를 팔 때는 환율이 목표 이상이 되면 체결한다 (같은 값 포함) */
    @ParameterizedTest
    @CsvSource({"849.99, false", "850.00, true", "850.01, true"})
    void sellingForeignTriggersAtOrAboveTarget(String midRate, boolean triggered) {
        ExchangeReservation reservation = reserve(Currency.JPY, Currency.KRW, "10000", "850");

        assertEquals(triggered, reservation.isTriggeredBy(QuoteFixtures.savedRate(ForeignCurrency.JPY, midRate)));
    }

    @Test
    void otherCurrencyRateNeverTriggers() {
        ExchangeReservation reservation = reserve(Currency.KRW, Currency.JPY, "100000", "830");

        assertFalse(reservation.isTriggeredBy(QuoteFixtures.savedRate(ForeignCurrency.CNY, "1.00")));
    }

    @Test
    void rateWithDifferentUnitIsRejected() {
        ExchangeReservation reservation = reserve(Currency.KRW, Currency.JPY, "100000", "830");
        ExchangeRate perYen = QuoteFixtures.savedRate(ForeignCurrency.JPY, "8.30");
        ReflectionTestUtils.setField(perYen, "unit", 1);

        assertThrows(IllegalStateException.class, () -> reservation.isTriggeredBy(perYen));
    }

    /** 금액은 견적과 같은 1회 한도·자릿수 규칙으로 검사한다 */
    @ParameterizedTest
    @CsvSource({"KRW, JPY, 999", "KRW, JPY, 2000001", "JPY, KRW, 100.5", "CNY, KRW, 10.001"})
    void rejectsAmountOutsideQuoteRules(Currency from, Currency to, String amount) {
        assertError(MoneyErrorCode.INVALID_AMOUNT, () -> reserve(from, to, amount, "830"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "830.001"})
    void rejectsTargetRateThatIsNotPositiveTwoDecimal(String targetRate) {
        assertError(ExchangeReservationErrorCode.INVALID_TARGET_RATE,
                () -> reserve(Currency.KRW, Currency.JPY, "100000", targetRate));
    }

    @Test
    void acceptsTrailingZerosBeyondTwoDecimals() {
        assertEquals(0, new BigDecimal("830.1").compareTo(
                reserve(Currency.KRW, Currency.JPY, "100000", "830.1000").getTargetRate()));
    }

    /** 만료 시각은 지금 이후, 최대 30일까지(포함) */
    @Test
    void expiryMustBeAfterNowAndWithinMaxPeriod() {
        assertError(ExchangeReservationErrorCode.INVALID_EXPIRY, () -> reserveExpiring(NOW));
        assertError(ExchangeReservationErrorCode.INVALID_EXPIRY,
                () -> reserveExpiring(NOW.plus(Duration.ofDays(30)).plusSeconds(1)));

        assertEquals(NOW.plus(Duration.ofDays(30)), reserveExpiring(NOW.plus(Duration.ofDays(30))).getExpiresAt());
        assertEquals(NOW.plusSeconds(1), reserveExpiring(NOW.plusSeconds(1)).getExpiresAt());
    }

    /** 같은 멱등 키의 재요청인지 판단: 지갑·통화쌍·금액·목표 환율(값 비교)·만료가 모두 같아야 한다 */
    @Test
    void sameRequestNeedsSameWalletPairAmountRateAndExpiry() {
        ExchangeReservation reservation = reserve(Currency.KRW, Currency.JPY, "100000", "830");
        QuoteCurrencies pair = new QuoteCurrencies(Currency.KRW, Currency.JPY);
        Money amount = new Money(Currency.KRW, 100_000);
        Instant expiresAt = NOW.plus(Duration.ofDays(7));

        assertTrue(reservation.isSameRequest(WALLET_ID, pair, amount, new BigDecimal("830.00"), expiresAt));
        assertFalse(reservation.isSameRequest(UUID.randomUUID(), pair, amount, new BigDecimal("830"), expiresAt));
        assertFalse(reservation.isSameRequest(WALLET_ID, new QuoteCurrencies(Currency.KRW, Currency.CNY),
                amount, new BigDecimal("830"), expiresAt));
        assertFalse(reservation.isSameRequest(WALLET_ID, pair, new Money(Currency.KRW, 200_000),
                new BigDecimal("830"), expiresAt));
        assertFalse(reservation.isSameRequest(WALLET_ID, pair, amount, new BigDecimal("831"), expiresAt));
        assertFalse(reservation.isSameRequest(WALLET_ID, pair, amount, new BigDecimal("830"), expiresAt.plusSeconds(1)));
    }

    @Test
    void expiresAtExpiryInstant() {
        ExchangeReservation reservation = reserveExpiring(NOW.plus(Duration.ofDays(1)));

        assertFalse(reservation.isExpired(reservation.getExpiresAt().minusMillis(1)));
        assertTrue(reservation.isExpired(reservation.getExpiresAt()));
    }

    private static ExchangeReservation reserve(Currency from, Currency to, String amount, String targetRate) {
        return ExchangeReservation.reserve(USER_ID, WALLET_ID, new IdempotencyKey("key-1"),
                new QuoteCurrencies(from, to), Money.of(from, new BigDecimal(amount)), new BigDecimal(targetRate),
                NOW.plus(Duration.ofDays(7)), QuoteFixtures.policy(), POLICY, NOW);
    }

    private static ExchangeReservation reserveExpiring(Instant expiresAt) {
        return ExchangeReservation.reserve(USER_ID, WALLET_ID, new IdempotencyKey("key-1"),
                new QuoteCurrencies(Currency.KRW, Currency.JPY), new Money(Currency.KRW, 100_000),
                new BigDecimal("830"), expiresAt, QuoteFixtures.policy(), POLICY, NOW);
    }

    private static void assertError(ErrorCode errorCode, Executable action) {
        assertEquals(errorCode, assertThrows(BusinessException.class, action).getErrorCode());
    }
}

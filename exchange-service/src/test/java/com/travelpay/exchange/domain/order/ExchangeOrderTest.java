package com.travelpay.exchange.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuoteStatus;
import com.travelpay.exchange.domain.quote.QuoteType;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 매매기준율 JPY 838.30(unit 100), 수수료율 1% 견적 기준입니다. */
class ExchangeOrderTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final IdempotencyKey KEY = new IdempotencyKey("key-1");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-29T03:00:00Z");
    private static final ExchangeRate JPY = QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30000000");

    @Test
    void copiesQuoteSnapshotWithoutRecalculation() {
        ExchangeQuote quote = savedQuote(Currency.KRW, Currency.JPY, "100000");

        ExchangeOrder order = ExchangeOrder.place(quote, WALLET_ID, KEY, ISSUED_AT.plusSeconds(60));

        assertEquals(USER_ID, order.getUserId());
        assertEquals(WALLET_ID, order.getWalletId());
        assertEquals("key-1", order.getIdempotencyKey());
        assertEquals(quote.getId(), order.getQuoteId());
        assertEquals(ISSUED_AT, order.getQuoteIssuedAt());
        assertEquals(ISSUED_AT.plusSeconds(300), order.getQuoteExpiresAt());
        assertEquals(JPY.getId(), order.getRateId());
        assertEquals(QuoteFixtures.RATE_DATE, order.getRateDate());
        assertEquals(new BigDecimal("838.30000000"), order.getAppliedRate());
        assertEquals(100, order.getRateUnit());
        assertEquals(new BigDecimal("0.01"), order.getFeeRate());
        assertEquals(new Money(Currency.KRW, 100000), order.fromMoney());
        assertEquals(new Money(Currency.KRW, 1000), order.feeMoney());
        assertEquals(new Money(Currency.JPY, 11928), order.toMoney());
        assertEquals(new Money(Currency.KRW, 101000), order.totalDebitMoney());
    }

    @Test
    void startsProcessingWithoutLedgerResult() {
        Instant requestedAt = ISSUED_AT.plusSeconds(60);

        ExchangeOrder order = ExchangeOrder.place(savedQuote(Currency.KRW, Currency.JPY, "100000"),
                WALLET_ID, KEY, requestedAt);

        assertEquals(ExchangeOrderStatus.PROCESSING, order.getStatus());
        assertEquals(requestedAt, order.getRequestedAt());
        assertNull(order.getFailureReason());
        assertNull(order.getLedgerTransactionId());
        assertNull(order.getSettledAt());
        assertEquals(0, order.getLedgerCheckCount());
        assertNull(order.getLastLedgerCheckedAt());
    }

    @Test
    void acceptsRequestJustBeforeExpiry() {
        ExchangeQuote quote = savedQuote(Currency.KRW, Currency.JPY, "100000");

        ExchangeOrder order = ExchangeOrder.place(quote, WALLET_ID, KEY, quote.getExpiresAt().minusNanos(1));

        assertEquals(ExchangeOrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void rejectsRequestAtExpiryInstant() {
        ExchangeQuote quote = savedQuote(Currency.KRW, Currency.JPY, "100000");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ExchangeOrder.place(quote, WALLET_ID, KEY, quote.getExpiresAt()));

        assertEquals(ExchangeOrderErrorCode.QUOTE_EXPIRED, exception.getErrorCode());
    }

    @Test
    void ignoresQuoteUsedStatus() {
        ExchangeQuote quote = savedQuote(Currency.KRW, Currency.JPY, "100000");
        ReflectionTestUtils.setField(quote, "status", QuoteStatus.USED);

        // 1회 사용은 주문 UNIQUE(quote_id)가 막는다. 견적 상태로 판단하지 않는다.
        ExchangeOrder order = ExchangeOrder.place(quote, WALLET_ID, KEY, ISSUED_AT.plusSeconds(60));

        assertEquals(ExchangeOrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void requiresSavedQuote() {
        ExchangeQuote unsaved = issue(Currency.KRW, Currency.JPY, "100000");

        assertThrows(NullPointerException.class,
                () -> ExchangeOrder.place(unsaved, WALLET_ID, KEY, ISSUED_AT.plusSeconds(60)));
    }

    @Test
    void sameRequestNeedsSameQuoteAndWallet() {
        ExchangeQuote quote = savedQuote(Currency.KRW, Currency.JPY, "100000");
        ExchangeOrder order = ExchangeOrder.place(quote, WALLET_ID, KEY, ISSUED_AT.plusSeconds(60));

        assertTrue(order.isSameRequest(quote.getId(), WALLET_ID));
        assertFalse(order.isSameRequest(UUID.randomUUID(), WALLET_ID));
        assertFalse(order.isSameRequest(quote.getId(), UUID.randomUUID()));
    }

    @Test
    void reExchangeWhenReceivingWon() {
        ExchangeOrder exchange = ExchangeOrder.place(savedQuote(Currency.KRW, Currency.JPY, "100000"),
                WALLET_ID, KEY, ISSUED_AT.plusSeconds(60));
        ExchangeOrder reExchange = ExchangeOrder.place(savedQuote(Currency.JPY, Currency.KRW, "1000"),
                WALLET_ID, KEY, ISSUED_AT.plusSeconds(60));

        assertFalse(exchange.isReExchange());
        assertTrue(reExchange.isReExchange());
        assertEquals(new Money(Currency.JPY, 10), reExchange.feeMoney());
        assertEquals(new Money(Currency.KRW, 8383), reExchange.toMoney());
    }

    @Test
    void placedHistoryRecordsCreationByUser() {
        ExchangeOrder order = ExchangeOrder.place(savedQuote(Currency.KRW, Currency.JPY, "100000"),
                WALLET_ID, KEY, ISSUED_AT.plusSeconds(60));
        UUID orderId = UUID.randomUUID();
        ReflectionTestUtils.setField(order, "id", orderId);

        ExchangeOrderStatusHistory history = order.placedHistory();

        assertEquals(orderId, history.getOrderId());
        assertNull(history.getFromStatus());
        assertEquals(ExchangeOrderStatus.PROCESSING, history.getToStatus());
        assertNull(history.getReason());
        assertEquals(ChangedByType.USER, history.getChangedByType());
    }

    @Test
    void placedHistoryNeedsSavedOrder() {
        ExchangeOrder order = ExchangeOrder.place(savedQuote(Currency.KRW, Currency.JPY, "100000"),
                WALLET_ID, KEY, ISSUED_AT.plusSeconds(60));

        assertThrows(IllegalStateException.class, order::placedHistory);
    }

    /** 저장된 것처럼 ID와 발급 시각(created_at)이 있는 견적입니다. */
    private static ExchangeQuote savedQuote(Currency from, Currency to, String amount) {
        ExchangeQuote quote = issue(from, to, amount);
        ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(quote, "createdAt", ISSUED_AT);
        return quote;
    }

    private static ExchangeQuote issue(Currency from, Currency to, String amount) {
        return ExchangeQuote.issue(USER_ID, QuoteType.USER, new QuoteCurrencies(from, to),
                Money.of(from, new BigDecimal(amount)), JPY, QuoteFixtures.policy(), ISSUED_AT);
    }
}

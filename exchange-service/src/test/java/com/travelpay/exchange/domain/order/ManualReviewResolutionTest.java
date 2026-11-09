package com.travelpay.exchange.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.ManualReviewResolution.Decision;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuoteType;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualReviewResolutionTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID MASTER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-29T03:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    @ParameterizedTest
    @EnumSource(ManualReviewReason.class)
    void postedWithMatchingEntriesInAnyOrderCompletesRegardlessOfReviewReason(ManualReviewReason reviewReason) {
        ExchangeOrder order = savedOrder();
        List<LedgerLookupResult.Entry> shuffled = new ArrayList<>(order.expectedLedgerEntries());
        Collections.reverse(shuffled);

        Decision decision = ManualReviewResolution.decide(
                order, reviewReason, new LedgerLookupResult.Posted(TX_ID, shuffled));

        Decision.Resolvable resolvable = assertInstanceOf(Decision.Resolvable.class, decision);
        assertTrue(resolvable.resolvable());
        assertEquals(ExchangeOrderStatus.COMPLETED, resolvable.toStatus());
        assertNull(resolvable.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_POSTED, resolvable.reason());
        assertEquals(TX_ID, resolvable.ledgerTransactionId());

        ManualReviewResolution resolution = resolvable.toResolution(MASTER_ID, "  원장 반영 확인  ", NOW);
        assertEquals(ExchangeOrderStatus.COMPLETED, resolution.status());
        assertEquals(TX_ID, resolution.orderLedgerTransactionId());
        assertEquals(TX_ID, resolution.historyLedgerTransactionId());
        assertNull(resolution.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_POSTED, resolution.reason());
        assertEquals("원장 반영 확인", resolution.memo());

        ExchangeOrderStatusHistory history = resolution.history(ORDER_ID);
        assertEquals(ExchangeOrderStatus.MANUAL_REVIEW, history.getFromStatus());
        assertEquals(ExchangeOrderStatus.COMPLETED, history.getToStatus());
        assertEquals("LEDGER_POSTED", history.getReason());
        assertEquals(ChangedByType.MASTER, history.getChangedByType());
        assertEquals(MASTER_ID, history.getChangedById());
        assertEquals(TX_ID, history.getLedgerTransactionId());
        assertEquals("원장 반영 확인", history.getMemo());
    }

    @Test
    void zeroFeeOrderMatchesFourEntriesWithoutFeePair() {
        ExchangeOrder order = savedOrder();
        ReflectionTestUtils.setField(order, "feeAmount", 0L);
        ReflectionTestUtils.setField(order, "totalDebitAmount", 100000L);

        List<LedgerLookupResult.Entry> expected = order.expectedLedgerEntries();
        assertEquals(4, expected.size());

        Decision decision = ManualReviewResolution.decide(
                order, ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG, new LedgerLookupResult.Posted(TX_ID, expected));

        Decision.Resolvable resolvable = assertInstanceOf(Decision.Resolvable.class, decision);
        assertEquals(ExchangeOrderStatus.COMPLETED, resolvable.toStatus());
    }

    @Test
    void postedWithMismatchedEntriesIsNotResolvableAndExposesBothEntryLists() {
        ExchangeOrder order = savedOrder();
        List<LedgerLookupResult.Entry> wrongAmount = new ArrayList<>(order.expectedLedgerEntries());
        wrongAmount.set(0, new LedgerLookupResult.Entry(
                "USER", WALLET_ID, "WALLET", "KRW", "DEBIT", 99999, "EXCHANGE_OUT"));

        Decision decision = ManualReviewResolution.decide(
                order, ManualReviewReason.LEDGER_CONFLICT, new LedgerLookupResult.Posted(TX_ID, wrongAmount));

        Decision.EntryMismatch mismatch = assertInstanceOf(Decision.EntryMismatch.class, decision);
        assertFalse(mismatch.resolvable());
        assertEquals(TX_ID, mismatch.ledgerTransactionId());
        assertEquals(order.expectedLedgerEntries(), mismatch.expectedEntries());
        assertEquals(wrongAmount, mismatch.actualEntries());
    }

    @ParameterizedTest
    @CsvSource({
            "INSUFFICIENT_BALANCE, INSUFFICIENT_BALANCE",
            "WALLET_SUSPENDED, WALLET_SUSPENDED",
            "ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND"
    })
    void rejectedWithKnownReasonKeepsReasonWhenNotConflict(String ledgerReason, ExchangeFailureReason expected) {
        ExchangeOrder order = savedOrder();

        Decision decision = ManualReviewResolution.decide(order, ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG,
                new LedgerLookupResult.Rejected(TX_ID, ledgerReason));

        Decision.Resolvable resolvable = assertInstanceOf(Decision.Resolvable.class, decision);
        assertEquals(ExchangeOrderStatus.FAILED, resolvable.toStatus());
        assertEquals(expected, resolvable.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_REJECTED, resolvable.reason());
        assertEquals(TX_ID, resolvable.ledgerTransactionId());

        // 주문의 ledger_transaction_id는 COMPLETED만 저장하므로 null이고, 이력에는 근거 거래 ID가 남는다
        ManualReviewResolution resolution = resolvable.toResolution(MASTER_ID, null, NOW);
        assertNull(resolution.orderLedgerTransactionId());
        assertEquals(TX_ID, resolution.historyLedgerTransactionId());
        assertEquals(expected, resolution.failureReason());
        assertEquals(TX_ID, resolution.history(ORDER_ID).getLedgerTransactionId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"DAILY_LIMIT_EXCEEDED", "UNKNOWN", ""})
    void rejectedWithUnknownReasonUsesProcessingError(String unknownReason) {
        ExchangeOrder order = savedOrder();

        Decision decision = ManualReviewResolution.decide(order, ManualReviewReason.LEDGER_UNKNOWN_REJECTION,
                new LedgerLookupResult.Rejected(TX_ID, unknownReason));

        Decision.Resolvable resolvable = assertInstanceOf(Decision.Resolvable.class, decision);
        assertEquals(ExchangeOrderStatus.FAILED, resolvable.toStatus());
        assertEquals(ExchangeFailureReason.PROCESSING_ERROR, resolvable.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_REJECTED, resolvable.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSUFFICIENT_BALANCE", "WALLET_SUSPENDED", "ACCOUNT_NOT_FOUND"})
    void rejectedOnLedgerConflictOrderAlwaysUsesProcessingError(String ledgerReason) {
        ExchangeOrder order = savedOrder();

        Decision decision = ManualReviewResolution.decide(order, ManualReviewReason.LEDGER_CONFLICT,
                new LedgerLookupResult.Rejected(TX_ID, ledgerReason));

        Decision.Resolvable resolvable = assertInstanceOf(Decision.Resolvable.class, decision);
        assertEquals(ExchangeOrderStatus.FAILED, resolvable.toStatus());
        assertEquals(ExchangeFailureReason.PROCESSING_ERROR, resolvable.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_REJECTED, resolvable.reason());
        assertEquals(TX_ID, resolvable.ledgerTransactionId());
    }

    @ParameterizedTest
    @EnumSource(ManualReviewReason.class)
    void notFoundCancelsWithNotProcessedAndNullLedgerTransactionId(ManualReviewReason reviewReason) {
        ExchangeOrder order = savedOrder();

        Decision decision = ManualReviewResolution.decide(order, reviewReason, new LedgerLookupResult.NotFound());

        Decision.Resolvable resolvable = assertInstanceOf(Decision.Resolvable.class, decision);
        assertEquals(ExchangeOrderStatus.FAILED, resolvable.toStatus());
        assertEquals(ExchangeFailureReason.NOT_PROCESSED, resolvable.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_NOT_FOUND, resolvable.reason());
        assertNull(resolvable.ledgerTransactionId());

        ManualReviewResolution resolution = resolvable.toResolution(MASTER_ID, "   ", NOW);
        assertNull(resolution.orderLedgerTransactionId());
        assertNull(resolution.historyLedgerTransactionId());
        assertNull(resolution.memo());
        assertNull(resolution.history(ORDER_ID).getLedgerTransactionId());
        assertEquals("LEDGER_NOT_FOUND", resolution.history(ORDER_ID).getReason());
    }

    @Test
    void rejectsUnavailableLookupOrInvalidResolutionInvariants() {
        ExchangeOrder order = savedOrder();

        assertThrows(IllegalArgumentException.class, () -> ManualReviewResolution.decide(
                order, ManualReviewReason.LEDGER_BAD_REQUEST, new LedgerLookupResult.Unavailable("HTTP 503")));
        assertThrows(IllegalArgumentException.class, () -> new ManualReviewResolution(
                ExchangeOrderStatus.COMPLETED, null, TX_ID, null,
                ManualReviewResolutionReason.LEDGER_POSTED, NOW, MASTER_ID, null));
        assertThrows(IllegalArgumentException.class, () -> new ManualReviewResolution(
                ExchangeOrderStatus.FAILED, TX_ID, TX_ID, ExchangeFailureReason.PROCESSING_ERROR,
                ManualReviewResolutionReason.LEDGER_REJECTED, NOW, MASTER_ID, null));
        assertThrows(IllegalArgumentException.class, () -> new ManualReviewResolution(
                ExchangeOrderStatus.COMPLETED, TX_ID, TX_ID, null,
                ManualReviewResolutionReason.LEDGER_POSTED, NOW, MASTER_ID, "a".repeat(256)));
    }

    private static ExchangeOrder savedOrder() {
        ExchangeQuote quote = ExchangeQuote.issue(USER_ID, QuoteType.USER,
                new QuoteCurrencies(Currency.KRW, Currency.JPY), Money.of(Currency.KRW, new BigDecimal("100000")),
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), QuoteFixtures.policy(), ISSUED_AT);
        ExchangeOrder order = OrderFixtures.placed(quote, WALLET_ID, "key-1");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }
}

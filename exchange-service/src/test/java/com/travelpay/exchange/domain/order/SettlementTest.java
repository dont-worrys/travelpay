package com.travelpay.exchange.domain.order;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult.InvalidKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Test
    void postedCompletesWithLedgerTransaction() {
        Settlement settlement = Settlement.from(new LedgerPostResult.Posted(TX_ID), NOW).orElseThrow();

        assertEquals(ExchangeOrderStatus.COMPLETED, settlement.status());
        assertEquals(TX_ID, settlement.ledgerTransactionId());
        assertEquals(NOW, settlement.settledAt());
        assertNull(settlement.failureReason());
    }

    @ParameterizedTest
    @CsvSource({
            "LEDGER_REJECTED_INSUFFICIENT_BALANCE, INSUFFICIENT_BALANCE",
            "LEDGER_REJECTED_WALLET_SUSPENDED, WALLET_SUSPENDED",
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND"
    })
    void knownRejectionFails(String rejectCode, ExchangeFailureReason expected) {
        Settlement settlement = Settlement.from(new LedgerPostResult.Rejected(rejectCode), NOW).orElseThrow();

        assertEquals(ExchangeOrderStatus.FAILED, settlement.status());
        assertEquals(expected, settlement.failureReason());
        assertEquals(NOW, settlement.settledAt());
        assertEquals(expected.name(), settlement.reason());
    }

    @Test
    void unknownRejectionGoesToManualReview() {
        Settlement settlement = Settlement.from(new LedgerPostResult.Rejected("LEDGER_REJECTED_DAILY_LIMIT"), NOW)
                .orElseThrow();

        assertManualReview(settlement, ManualReviewReason.LEDGER_UNKNOWN_REJECTION);
    }

    @Test
    void unknownResultChangesNothing() {
        assertEquals(Optional.empty(), Settlement.from(new LedgerPostResult.Unknown("timeout"), NOW));
    }

    @ParameterizedTest
    @CsvSource({
            "BAD_REQUEST, LEDGER_BAD_REQUEST",
            "CONFLICT, LEDGER_CONFLICT",
            "MALFORMED_RESPONSE, LEDGER_MALFORMED_RESPONSE"
    })
    void invalidResponseGoesToManualReview(InvalidKind kind, ManualReviewReason expected) {
        assertManualReview(Settlement.from(new LedgerPostResult.Invalid(kind), NOW).orElseThrow(), expected);
    }

    @Test
    void historyRecordsChangeFromProcessingBySystem() {
        ExchangeOrderStatusHistory history = Settlement.failed(ExchangeFailureReason.INSUFFICIENT_BALANCE, NOW)
                .history(ORDER_ID);

        assertEquals(ORDER_ID, history.getOrderId());
        assertEquals(ExchangeOrderStatus.PROCESSING, history.getFromStatus());
        assertEquals(ExchangeOrderStatus.FAILED, history.getToStatus());
        assertEquals("INSUFFICIENT_BALANCE", history.getReason());
        assertEquals(ChangedByType.SYSTEM, history.getChangedByType());
    }

    @Test
    void rejectsValuesThatDoNotMatchStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(ExchangeOrderStatus.PROCESSING, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(ExchangeOrderStatus.COMPLETED, null, null, NOW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(ExchangeOrderStatus.FAILED, TX_ID, ExchangeFailureReason.WALLET_SUSPENDED,
                        NOW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(ExchangeOrderStatus.MANUAL_REVIEW, null, null, NOW, "LEDGER_CONFLICT"));
    }

    private static void assertManualReview(Settlement settlement, ManualReviewReason reason) {
        assertEquals(ExchangeOrderStatus.MANUAL_REVIEW, settlement.status());
        assertEquals(reason.name(), settlement.reason());
        assertNull(settlement.settledAt());
        assertNull(settlement.ledgerTransactionId());
        assertNull(settlement.failureReason());
        assertTrue(!settlement.status().isSettled());
    }
}

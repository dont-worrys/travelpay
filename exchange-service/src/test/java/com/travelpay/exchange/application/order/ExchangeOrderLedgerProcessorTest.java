package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult.InvalidKind;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.order.Settlement;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeOrderLedgerProcessorTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-01T03:00:00Z");
    private static final Instant NOW = ISSUED_AT.plusSeconds(120);
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeOrderStatusHistoryRepository historyRepository =
            mock(ExchangeOrderStatusHistoryRepository.class);
    private final LedgerClient ledgerClient = mock(LedgerClient.class);
    private final ExchangeOrderLedgerProcessor processor = new ExchangeOrderLedgerProcessor(orderRepository,
            historyRepository, ledgerClient, TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    private ExchangeOrder order;
    private ExchangeOrder reloaded;

    @BeforeEach
    void setUp() {
        order = processingOrder();
        reloaded = processingOrder();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(reloaded));
        when(orderRepository.claimLedgerCheck(ORDER_ID, 0, NOW)).thenReturn(true);
        when(orderRepository.settleIfProcessing(eq(ORDER_ID), any(), eq(NOW))).thenReturn(true);
    }

    @Test
    void postedCompletesOrderWithHistory() {
        when(ledgerClient.post(order.toLedgerPosting())).thenReturn(new LedgerPostResult.Posted(TX_ID));

        ExchangeOrder result = processor.process(order);

        assertSame(reloaded, result);
        verify(orderRepository).settleIfProcessing(ORDER_ID, Settlement.completed(TX_ID, NOW), NOW);
        ExchangeOrderStatusHistory history = savedHistory();
        assertEquals(ExchangeOrderStatus.PROCESSING, history.getFromStatus());
        assertEquals(ExchangeOrderStatus.COMPLETED, history.getToStatus());
    }

    @Test
    void knownRejectionFailsOrder() {
        when(ledgerClient.post(any())).thenReturn(
                new LedgerPostResult.Rejected("LEDGER_REJECTED_INSUFFICIENT_BALANCE"));

        processor.process(order);

        verify(orderRepository).settleIfProcessing(ORDER_ID,
                Settlement.failed(ExchangeFailureReason.INSUFFICIENT_BALANCE, NOW), NOW);
        assertEquals(ExchangeOrderStatus.FAILED, savedHistory().getToStatus());
    }

    @Test
    void conflictGoesToManualReview() {
        when(ledgerClient.post(any())).thenReturn(new LedgerPostResult.Invalid(InvalidKind.CONFLICT));

        processor.process(order);

        verify(orderRepository).settleIfProcessing(ORDER_ID,
                Settlement.manualReview(ManualReviewReason.LEDGER_CONFLICT), NOW);
        assertEquals("LEDGER_CONFLICT", savedHistory().getReason());
    }

    @Test
    void unknownResultLeavesOrderProcessing() {
        when(ledgerClient.post(any())).thenReturn(new LedgerPostResult.Unknown("HTTP 503"));

        ExchangeOrder result = processor.process(order);

        assertSame(reloaded, result);
        verify(orderRepository).claimLedgerCheck(ORDER_ID, 0, NOW);
        verify(orderRepository, never()).settleIfProcessing(any(), any(), any());
        verifyNoInteractions(historyRepository);
    }

    @Test
    void doesNotCallLedgerWhenAnotherFlowClaimedFirst() {
        when(orderRepository.claimLedgerCheck(ORDER_ID, 0, NOW)).thenReturn(false);

        assertSame(reloaded, processor.process(order));

        verifyNoInteractions(ledgerClient, historyRepository);
    }

    @Test
    void leavesNoHistoryWhenAnotherFlowSettledFirst() {
        when(ledgerClient.post(any())).thenReturn(new LedgerPostResult.Posted(TX_ID));
        when(orderRepository.settleIfProcessing(eq(ORDER_ID), any(), eq(NOW))).thenReturn(false);

        processor.process(order);

        verifyNoInteractions(historyRepository);
    }

    @Test
    void settledOrderIsReturnedAsIs() {
        ReflectionTestUtils.setField(order, "status", ExchangeOrderStatus.COMPLETED);

        assertSame(order, processor.process(order));

        verifyNoInteractions(ledgerClient);
        verify(orderRepository, never()).claimLedgerCheck(any(), anyInt(), any());
    }

    @Test
    void orderUnconfirmedForADayGoesToManualReviewWithoutCallingLedger() {
        ReflectionTestUtils.setField(order, "requestedAt", NOW.minus(Duration.ofHours(24)));

        processor.process(order);

        verify(orderRepository).settleIfProcessing(ORDER_ID,
                Settlement.manualReview(ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG), NOW);
        verifyNoInteractions(ledgerClient);
        verify(orderRepository, never()).claimLedgerCheck(any(), anyInt(), any());
    }

    @Test
    void orderJustUnderADayIsStillSent() {
        ReflectionTestUtils.setField(order, "requestedAt", NOW.minus(Duration.ofHours(24)).plusNanos(1000));
        when(ledgerClient.post(any())).thenReturn(new LedgerPostResult.Unknown("timeout"));

        processor.process(order);

        verify(ledgerClient).post(any());
    }

    private ExchangeOrderStatusHistory savedHistory() {
        ArgumentCaptor<ExchangeOrderStatusHistory> captor = ArgumentCaptor.forClass(ExchangeOrderStatusHistory.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }

    private static ExchangeOrder processingOrder() {
        ExchangeQuote quote = OrderFixtures.wonToYenQuote(UUID.randomUUID(),
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), ISSUED_AT);
        ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(quote, "createdAt", ISSUED_AT);
        ExchangeOrder placed = OrderFixtures.placed(quote, UUID.randomUUID(), "key-1");
        ReflectionTestUtils.setField(placed, "id", ORDER_ID);
        return placed;
    }
}

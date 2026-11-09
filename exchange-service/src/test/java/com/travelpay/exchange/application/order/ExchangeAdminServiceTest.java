package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerDirection;
import com.travelpay.exchange.domain.ledger.LedgerEntry;
import com.travelpay.exchange.domain.ledger.LedgerEntryType;
import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.ledger.LedgerSystemAccount;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.domain.order.ManualReviewResolution;
import com.travelpay.exchange.domain.order.ManualReviewResolutionReason;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeAdminServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-11-01T05:00:00Z");

    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeOrderStatusHistoryRepository historyRepository = mock(ExchangeOrderStatusHistoryRepository.class);
    private final ExchangeOrderQueryRepository orderQueryRepository = mock(ExchangeOrderQueryRepository.class);
    private final LedgerClient ledgerClient = mock(LedgerClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TransactionOperations transactionOperations = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    private ExchangeAdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new ExchangeAdminService(
                orderRepository,
                historyRepository,
                orderQueryRepository,
                ledgerClient,
                clock,
                transactionOperations
        );
    }

    @Test
    void getManualReviewsValidatesPageParameters() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> adminService.getManualReviews(ACTOR_ID, null, -1, 20));
        assertEquals(CommonErrorCode.INVALID_REQUEST, e1.getErrorCode());

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> adminService.getManualReviews(ACTOR_ID, null, 0, 0));
        assertEquals(CommonErrorCode.INVALID_REQUEST, e2.getErrorCode());
    }

    @Test
    void getManualReviewsCapsMaxPageSize() {
        when(orderQueryRepository.findManualReviews(eq(ManualReviewReason.LEDGER_CONFLICT), eq(PageRequest.of(0, 100))))
                .thenReturn(new PageImpl<>(List.of()));

        adminService.getManualReviews(ACTOR_ID, ManualReviewReason.LEDGER_CONFLICT, 0, 500);

        verify(orderQueryRepository).findManualReviews(ManualReviewReason.LEDGER_CONFLICT, PageRequest.of(0, 100));
    }

    @Test
    void getResolutionsValidatesPageParameters() {
        ExchangeResolutionCondition all = new ExchangeResolutionCondition(null, null, null, null);

        assertEquals(CommonErrorCode.INVALID_REQUEST, assertThrows(BusinessException.class,
                () -> adminService.getResolutions(ACTOR_ID, all, -1, 20)).getErrorCode());
        assertEquals(CommonErrorCode.INVALID_REQUEST, assertThrows(BusinessException.class,
                () -> adminService.getResolutions(ACTOR_ID, all, 0, 0)).getErrorCode());
    }

    /** 기간은 from 포함·to 제외라, from이 to와 같거나 늦으면 아무것도 나올 수 없는 잘못된 요청입니다. */
    @Test
    void getResolutionsRejectsFromNotBeforeTo() {
        ExchangeResolutionCondition same = new ExchangeResolutionCondition(null, null, NOW, NOW);
        ExchangeResolutionCondition reversed = new ExchangeResolutionCondition(null, null, NOW, NOW.minusSeconds(1));

        assertEquals(CommonErrorCode.INVALID_REQUEST, assertThrows(BusinessException.class,
                () -> adminService.getResolutions(ACTOR_ID, same, 0, 20)).getErrorCode());
        assertEquals(CommonErrorCode.INVALID_REQUEST, assertThrows(BusinessException.class,
                () -> adminService.getResolutions(ACTOR_ID, reversed, 0, 20)).getErrorCode());
    }

    @Test
    void getResolutionsPassesConditionAndCapsMaxPageSize() {
        ExchangeResolutionCondition condition =
                new ExchangeResolutionCondition(ACTOR_ID, null, NOW.minusSeconds(3600), NOW);
        when(orderQueryRepository.findResolutions(condition, PageRequest.of(2, 100)))
                .thenReturn(new PageImpl<>(List.of()));

        adminService.getResolutions(ACTOR_ID, condition, 2, 500);

        verify(orderQueryRepository).findResolutions(condition, PageRequest.of(2, 100));
    }

    @Test
    void getManualReviewThrowsWhenOrderNotFound() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminService.getManualReview(ACTOR_ID, ORDER_ID));
        assertEquals(ExchangeOrderErrorCode.ORDER_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void getManualReviewThrowsWhenOrderIsNotManualReview() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.PROCESSING);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminService.getManualReview(ACTOR_ID, ORDER_ID));
        assertEquals(ExchangeOrderErrorCode.NOT_MANUAL_REVIEW, e.getErrorCode());
    }

    @Test
    void getManualReviewReturnsLookupFailedWhenLedgerIsUnavailable() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_CONFLICT));
        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.Unavailable("timeout"));

        AdminExchangeReviewDetailResult result = adminService.getManualReview(ACTOR_ID, ORDER_ID);

        assertTrue(result.ledgerLookupFailed());
        assertNull(result.ledger());
        assertNull(result.expected());
        assertEquals(order, result.order());
        assertEquals(ManualReviewReason.LEDGER_CONFLICT, result.reviewReason());
    }

    @Test
    void getManualReviewReturnsResolvableWhenPostedAndEntriesMatch() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG));

        UUID txId = UUID.randomUUID();
        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.Posted(txId, order.expectedLedgerEntries()));

        AdminExchangeReviewDetailResult result = adminService.getManualReview(ACTOR_ID, ORDER_ID);

        assertFalse(result.ledgerLookupFailed());
        assertNotNull(result.ledger());
        assertEquals("POSTED", result.ledger().status());
        assertEquals(txId, result.ledger().transactionId());
        assertNotNull(result.expected());
        assertTrue(result.expected().resolvable());
        assertEquals(ExchangeOrderStatus.COMPLETED, result.expected().toStatus());
        assertNull(result.expected().failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_POSTED, result.expected().reason());
    }

    @Test
    void getManualReviewReturnsEntryMismatchWhenPostedAndEntriesMismatch() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_CONFLICT));

        UUID txId = UUID.randomUUID();
        List<LedgerLookupResult.Entry> differentEntries = List.of(
                new LedgerLookupResult.Entry("USER", WALLET_ID, "WALLET", "KRW", "DEBIT", 999_999L, "EXCHANGE_OUT")
        );
        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.Posted(txId, differentEntries));

        AdminExchangeReviewDetailResult result = adminService.getManualReview(ACTOR_ID, ORDER_ID);

        assertFalse(result.ledgerLookupFailed());
        assertNotNull(result.expected());
        assertFalse(result.expected().resolvable());
        assertNull(result.expected().toStatus());
        assertNotNull(result.expected().expectedEntries());
        assertNotNull(result.expected().actualEntries());
    }

    @Test
    void resolveManualReviewThrowsWhenLedgerLookupUnavailable() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_CONFLICT));
        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.Unavailable("connect failed"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminService.resolveManualReview(ACTOR_ID, ORDER_ID, "memo"));
        assertEquals(ExchangeOrderErrorCode.LEDGER_LOOKUP_UNAVAILABLE, e.getErrorCode());
    }

    @Test
    void resolveManualReviewThrowsWhenEntryMismatch() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_CONFLICT));

        UUID txId = UUID.randomUUID();
        List<LedgerLookupResult.Entry> mismatchedEntries = List.of(
                new LedgerLookupResult.Entry("USER", WALLET_ID, "WALLET", "KRW", "DEBIT", 1L, "EXCHANGE_OUT")
        );
        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.Posted(txId, mismatchedEntries));

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminService.resolveManualReview(ACTOR_ID, ORDER_ID, "memo"));
        assertEquals(ExchangeOrderErrorCode.LEDGER_CORRECTION_REQUIRED, e.getErrorCode());
    }

    @Test
    void resolveManualReviewThrows409WhenConcurrentUpdateFails() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG));

        UUID txId = UUID.randomUUID();
        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.Posted(txId, order.expectedLedgerEntries()));
        when(orderRepository.resolveIfManualReview(eq(ORDER_ID), any(), eq(NOW)))
                .thenReturn(false); // another admin updated first

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminService.resolveManualReview(ACTOR_ID, ORDER_ID, "memo"));
        assertEquals(ExchangeOrderErrorCode.NOT_MANUAL_REVIEW, e.getErrorCode());
    }

    @Test
    void resolveManualReviewSuccessfullyCompletesWhenPostedAndEntriesMatch() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG));

        UUID txId = UUID.randomUUID();
        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.Posted(txId, order.expectedLedgerEntries()));
        when(orderRepository.resolveIfManualReview(eq(ORDER_ID), any(), eq(NOW)))
                .thenReturn(true);

        AdminExchangeResolveResult result = adminService.resolveManualReview(ACTOR_ID, ORDER_ID, "정상 확인");

        assertEquals(ORDER_ID, result.orderId());
        assertEquals(ExchangeOrderStatus.COMPLETED, result.status());
        assertNull(result.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_POSTED, result.reason());
        assertEquals(txId, result.ledgerTransactionId());
        assertEquals(NOW, result.settledAt());

        verify(orderRepository).resolveIfManualReview(eq(ORDER_ID), any(ManualReviewResolution.class), eq(NOW));
        verify(historyRepository).save(any());
    }

    @Test
    void resolveManualReviewSuccessfullyFailsWhenLedgerNotFound() {
        ExchangeOrder order = orderWithStatus(ExchangeOrderStatus.MANUAL_REVIEW);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(historyRepository.findLatestManualReviewReason(ORDER_ID))
                .thenReturn(Optional.of(ManualReviewReason.LEDGER_BAD_REQUEST));

        when(ledgerClient.findByOrderId(ORDER_ID))
                .thenReturn(new LedgerLookupResult.NotFound());
        when(orderRepository.resolveIfManualReview(eq(ORDER_ID), any(), eq(NOW)))
                .thenReturn(true);

        AdminExchangeResolveResult result = adminService.resolveManualReview(ACTOR_ID, ORDER_ID, null);

        assertEquals(ORDER_ID, result.orderId());
        assertEquals(ExchangeOrderStatus.FAILED, result.status());
        assertEquals(ExchangeFailureReason.NOT_PROCESSED, result.failureReason());
        assertEquals(ManualReviewResolutionReason.LEDGER_NOT_FOUND, result.reason());
        assertNull(result.ledgerTransactionId());
        assertEquals(NOW, result.settledAt());

        verify(historyRepository).save(any());
    }

    private static ExchangeOrder orderWithStatus(ExchangeOrderStatus status) {
        Instant issuedAt = Instant.parse("2026-10-29T03:00:00Z");
        ExchangeQuote quote = OrderFixtures.wonToYenQuote(USER_ID,
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), issuedAt);
        ExchangeOrder order = OrderFixtures.placed(quote, WALLET_ID, "idemp-key-1");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }
}

package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.ChangedByType;
import com.travelpay.exchange.domain.order.DuplicateExchangeOrderException;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteErrorCode;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeOrderServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-29T03:00:00Z");
    private static final Instant NOW = ISSUED_AT.plusSeconds(60);
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String KEY = "key-1";

    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeOrderStatusHistoryRepository historyRepository =
            mock(ExchangeOrderStatusHistoryRepository.class);
    private final ExchangeQuoteRepository quoteRepository = mock(ExchangeQuoteRepository.class);
    private final WalletBalanceReader walletBalanceReader = mock(WalletBalanceReader.class);
    private final ExchangeOrderService service = new ExchangeOrderService(orderRepository, historyRepository,
            quoteRepository, walletBalanceReader, TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    private ExchangeQuote quote;

    @BeforeEach
    void setUp() {
        quote = savedQuote();
        when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.empty());
        when(quoteRepository.findByIdAndUserId(quote.getId(), USER_ID)).thenReturn(Optional.of(quote));
        when(walletBalanceReader.read(USER_ID)).thenReturn(Optional.of(wallet(WALLET_ID, true, true, true)));
        when(orderRepository.save(any())).thenAnswer(invocation -> {
            ExchangeOrder order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
            return order;
        });
    }

    @Test
    void placesProcessingOrderWithCreationHistory() {
        ExchangeOrder order = service.place(command(quote.getId(), WALLET_ID));

        verify(orderRepository).save(order);
        assertEquals(ExchangeOrderStatus.PROCESSING, order.getStatus());
        assertEquals(NOW, order.getRequestedAt());
        assertEquals(quote.getId(), order.getQuoteId());
        assertEquals(new Money(Currency.KRW, 101000), order.totalDebitMoney());

        ArgumentCaptor<ExchangeOrderStatusHistory> history = ArgumentCaptor.forClass(ExchangeOrderStatusHistory.class);
        verify(historyRepository).save(history.capture());
        assertEquals(order.getId(), history.getValue().getOrderId());
        assertEquals(ExchangeOrderStatus.PROCESSING, history.getValue().getToStatus());
        assertEquals(ChangedByType.USER, history.getValue().getChangedByType());
    }

    @Test
    void replaysExistingOrderOfSameKeyBeforeCheckingQuoteAndWallet() {
        ExchangeOrder existing = OrderFixtures.placed(quote, WALLET_ID, KEY);
        when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.of(existing));

        assertSame(existing, service.place(command(quote.getId(), WALLET_ID)));
        verifyNoInteractions(quoteRepository, walletBalanceReader);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void rejectsSameKeyForAnotherRequest() {
        when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.of(OrderFixtures.placed(quote, WALLET_ID, KEY)));

        assertError(ExchangeOrderErrorCode.IDEMPOTENCY_KEY_REUSED,
                () -> service.place(command(UUID.randomUUID(), WALLET_ID)));
    }

    @Test
    void rejectsInvalidKeyBeforeLookup() {
        assertError(ExchangeOrderErrorCode.INVALID_IDEMPOTENCY_KEY,
                () -> service.place(new PlaceOrderCommand(USER_ID, "key 1", quote.getId(), WALLET_ID)));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void rejectsQuoteNotFoundOrOfAnotherUser() {
        assertError(ExchangeQuoteErrorCode.QUOTE_NOT_FOUND,
                () -> service.place(command(UUID.randomUUID(), WALLET_ID)));
    }

    @Test
    void rejectsExpiredQuoteWithoutCallingWallet() {
        ReflectionTestUtils.setField(quote, "expiresAt", NOW);

        assertError(ExchangeOrderErrorCode.QUOTE_EXPIRED, () -> service.place(command(quote.getId(), WALLET_ID)));
        verifyNoInteractions(walletBalanceReader);
    }

    @Test
    void rejectsWhenUserHasNoWallet() {
        when(walletBalanceReader.read(USER_ID)).thenReturn(Optional.empty());

        assertError(ExchangeOrderErrorCode.WALLET_NOT_FOUND, () -> service.place(command(quote.getId(), WALLET_ID)));
    }

    @Test
    void treatsWalletOfAnotherUserAsNotFound() {
        assertError(ExchangeOrderErrorCode.WALLET_NOT_FOUND,
                () -> service.place(command(quote.getId(), UUID.randomUUID())));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void rejectsInactiveWallet() {
        when(walletBalanceReader.read(USER_ID)).thenReturn(Optional.of(wallet(WALLET_ID, false, true, true)));

        assertError(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE,
                () -> service.place(command(quote.getId(), WALLET_ID)));
    }

    @Test
    void rejectsInactiveSendingAccount() {
        when(walletBalanceReader.read(USER_ID)).thenReturn(Optional.of(wallet(WALLET_ID, true, false, true)));

        assertError(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE,
                () -> service.place(command(quote.getId(), WALLET_ID)));
    }

    @Test
    void rejectsInactiveReceivingAccount() {
        when(walletBalanceReader.read(USER_ID)).thenReturn(Optional.of(wallet(WALLET_ID, true, true, false)));

        assertError(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE,
                () -> service.place(command(quote.getId(), WALLET_ID)));
    }

    @Test
    void rejectsWhenWalletCannotBeChecked() {
        when(walletBalanceReader.read(USER_ID)).thenThrow(new WalletBalanceFetchException("timeout"));

        assertError(ExchangeOrderErrorCode.WALLET_SERVICE_UNAVAILABLE,
                () -> service.place(command(quote.getId(), WALLET_ID)));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void concurrentRequestOfSameKeyReturnsWinnerOrder() {
        ExchangeOrder winner = OrderFixtures.placed(quote, WALLET_ID, KEY);
        doThrow(new DuplicateExchangeOrderException("dup", null)).when(orderRepository).save(any());
        when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.empty(), Optional.of(winner));

        assertSame(winner, service.place(command(quote.getId(), WALLET_ID)));
        verify(historyRepository, never()).save(any());
    }

    @Test
    void quoteUsedByAnotherKeyIsAlreadyUsed() {
        doThrow(new DuplicateExchangeOrderException("dup", null)).when(orderRepository).save(any());

        assertError(ExchangeOrderErrorCode.QUOTE_ALREADY_USED,
                () -> service.place(command(quote.getId(), WALLET_ID)));
    }

    @Test
    void concurrentRequestOfSameKeyForAnotherRequestIsReused() {
        ExchangeOrder winner = OrderFixtures.placed(savedQuote(), WALLET_ID, KEY);
        doThrow(new DuplicateExchangeOrderException("dup", null)).when(orderRepository).save(any());
        when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.empty(), Optional.of(winner));

        assertError(ExchangeOrderErrorCode.IDEMPOTENCY_KEY_REUSED,
                () -> service.place(command(quote.getId(), WALLET_ID)));
    }

    @Test
    void findsOwnOrderOnly() {
        ExchangeOrder order = OrderFixtures.placed(quote, WALLET_ID, KEY);
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndUserId(orderId, USER_ID)).thenReturn(Optional.of(order));

        assertSame(order, service.find(USER_ID, orderId));
        assertError(ExchangeOrderErrorCode.ORDER_NOT_FOUND, () -> service.find(USER_ID, UUID.randomUUID()));
    }

    private static PlaceOrderCommand command(UUID quoteId, UUID walletId) {
        return new PlaceOrderCommand(USER_ID, KEY, quoteId, walletId);
    }

    /** 원화 10만 원 → 엔화 견적입니다. 저장된 것처럼 ID와 발급 시각이 있습니다. */
    private static ExchangeQuote savedQuote() {
        ExchangeQuote saved = OrderFixtures.wonToYenQuote(USER_ID,
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), ISSUED_AT);
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(saved, "createdAt", ISSUED_AT);
        return saved;
    }

    /** 원화(보내는 통화)와 엔화(받는 통화) 계정이 있는 지갑입니다. */
    private static WalletBalances wallet(UUID walletId, boolean walletActive, boolean wonActive, boolean yenActive) {
        return new WalletBalances(walletId, walletActive, Map.of(
                Currency.KRW, new WalletBalances.Account(new Money(Currency.KRW, 500_000), wonActive),
                Currency.JPY, new WalletBalances.Account(new Money(Currency.JPY, 0), yenActive)));
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BusinessException.class, call).getErrorCode());
    }
}

package com.travelpay.payment.application.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.application.payment.client.ledger.LedgerClient;
import com.travelpay.payment.application.payment.client.ledger.LedgerResult;
import com.travelpay.payment.application.payment.client.ledger.LedgerTransactionCommand;
import com.travelpay.payment.application.payment.client.wallet.CurrencyWallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.PaymentResult;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Merchant;
import com.travelpay.payment.domain.payment.MerchantRepository;
import com.travelpay.payment.domain.payment.MerchantStatus;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WALLET_CURRENCY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LEDGER_ACCOUNT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String KEY = "payment-key-1";

    private PaymentRepository paymentRepository;
    private PaymentHistoryRepository paymentHistoryRepository;
    private MerchantRepository merchantRepository;
    private WalletClient walletClient;
    private LedgerClient ledgerClient;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentHistoryRepository = mock(PaymentHistoryRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        walletClient = mock(WalletClient.class);
        ledgerClient = mock(LedgerClient.class);
        paymentService = new PaymentService(
                paymentRepository, paymentHistoryRepository, merchantRepository, walletClient, ledgerClient);

        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void approvesPaymentWhenLedgerApproves() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals("테스트 가맹점", result.merchantName());
        verify(ledgerClient, times(1)).createTransaction(any());

        List<PaymentHistory> histories = savedHistories(2);
        assertNull(histories.get(0).getPreviousStatus());
        assertEquals(PaymentStatus.PENDING, histories.get(0).getCurrentStatus());
        assertEquals(PaymentActor.USER, histories.get(0).getCreatedBy());
        assertEquals(PaymentStatus.PENDING, histories.get(1).getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, histories.get(1).getCurrentStatus());
        assertEquals(PaymentActor.SYSTEM, histories.get(1).getCreatedBy());
    }

    @Test
    void sendsWalletLedgerAccountAndPaymentReferenceToLedger() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));

        paymentService.paymentRequest(command(10_000L));

        ArgumentCaptor<LedgerTransactionCommand> captor = ArgumentCaptor.forClass(LedgerTransactionCommand.class);
        verify(ledgerClient).createTransaction(captor.capture());
        LedgerTransactionCommand sent = captor.getValue();
        assertEquals(LEDGER_ACCOUNT_ID, sent.debitAccountId());
        assertEquals(CurrencyCode.KRW, sent.currency());
        assertEquals(10_000L, sent.amount());
        assertEquals("PAYMENT", sent.referenceType());
        assertEquals(MERCHANT_ID, sent.merchantId());
    }

    @Test
    void throwsDeclinedButKeepsDeclinedRecordWhenLedgerDeclines() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.declined("잔액이 부족합니다."));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(2_000_000L)));

        assertEquals(PaymentErrorCode.DECLINED, exception.getErrorCode());

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getAllValues().get(1);
        assertEquals(PaymentStatus.DECLINED, saved.getStatus());
        assertEquals("잔액이 부족합니다.", saved.getDeclineReason());

        List<PaymentHistory> histories = savedHistories(2);
        assertEquals(PaymentStatus.DECLINED, histories.get(1).getCurrentStatus());
        assertEquals("잔액이 부족합니다.", histories.get(1).getReason());
    }

    @Test
    void keepsPendingWhenLedgerResultIsUnknown() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.unknown());

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.PENDING, result.status());
        verify(paymentRepository, times(1)).save(any(Payment.class));
        List<PaymentHistory> histories = savedHistories(1);
        assertEquals(PaymentStatus.PENDING, histories.get(0).getCurrentStatus());
    }

    @Test
    void throwsWhenMerchantNotFound() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(walletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenMerchantNotActive() {
        givenMerchant(MerchantStatus.SUSPENDED, CurrencyCode.KRW);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_ACTIVE, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(walletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenCurrencyDiffersFromMerchant() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.JPY);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.CURRENCY_MISMATCH, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(walletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenCurrencyWalletNotFound() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        when(walletClient.findCurrencyWallet(USER_ID, CurrencyCode.KRW)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.WALLET_NOT_FOUND, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void returnsExistingPaymentForSameIdempotentRequest() {
        Payment existing = existingPayment(10_000L);
        existing.approvedHistory(LEDGER_TRANSACTION_ID);
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(existing));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals(10_000L, result.amount());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(merchantRepository, walletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenIdempotencyKeyReusedWithDifferentRequest() {
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(existingPayment(10_000L)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(50_000L)));

        assertEquals(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(merchantRepository, walletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsDeclinedAgainForSameRequestOfDeclinedPayment() {
        Payment existing = existingPayment(2_000_000L);
        existing.declinedHistory("잔액이 부족합니다.");
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(2_000_000L)));

        assertEquals(PaymentErrorCode.DECLINED, exception.getErrorCode());
        verifyNoInteractions(merchantRepository, walletClient, ledgerClient, paymentHistoryRepository);
    }

    private PaymentCommand command(long amount) {
        return new PaymentCommand(USER_ID, KEY, MERCHANT_ID, CurrencyCode.KRW, amount, "테스트 주문");
    }

    private Payment existingPayment(long amount) {
        return Payment.create(USER_ID, WALLET_CURRENCY_ID, KEY, MERCHANT_ID, "테스트 가맹점", "테스트 주문",
                CurrencyCode.KRW, amount);
    }

    private void givenMerchant(MerchantStatus status, CurrencyCode currency) {
        Merchant merchant = mock(Merchant.class);
        when(merchant.getId()).thenReturn(MERCHANT_ID);
        when(merchant.getName()).thenReturn("테스트 가맹점");
        when(merchant.getStatus()).thenReturn(status);
        when(merchant.getCurrency()).thenReturn(currency);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
    }

    private void givenWallet() {
        when(walletClient.findCurrencyWallet(USER_ID, CurrencyCode.KRW))
                .thenReturn(Optional.of(new CurrencyWallet(WALLET_CURRENCY_ID, LEDGER_ACCOUNT_ID, CurrencyCode.KRW)));
    }

    private List<PaymentHistory> savedHistories(int expectedCount) {
        ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
        verify(paymentHistoryRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void returnsOwnPayment() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(existingPayment(10_000L)));

        PaymentResult result = paymentService.getPayment(USER_ID, PAYMENT_ID);

        assertEquals(PaymentStatus.PENDING, result.status());
        assertEquals(10_000L, result.amount());
    }

    @Test
    void throwsNotFoundWhenPaymentDoesNotExist() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getPayment(USER_ID, PAYMENT_ID));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void throwsNotFoundForOtherUsersPayment() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(existingPayment(10_000L)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getPayment(OTHER_USER_ID, PAYMENT_ID));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
    }
}

package com.travelpay.payment.application.payment;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.payment.client.ledger.LedgerClient;
import com.travelpay.payment.application.payment.client.ledger.LedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.LedgerResult;
import com.travelpay.payment.application.payment.client.ledger.LedgerTransactionCommand;
import com.travelpay.payment.application.payment.client.wallet.Wallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.PaymentListResult;
import com.travelpay.payment.application.payment.dto.PaymentResult;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Merchant;
import com.travelpay.payment.domain.payment.MerchantRepository;
import com.travelpay.payment.domain.payment.MerchantStatus;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.PaymentDeclinedException;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundRepository;
import com.travelpay.payment.domain.payment.RefundStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final UUID WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID REFUND_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID REFUND_TRANSACTION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_PAYMENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID OLDER_PAYMENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final Instant CREATED_AT = Instant.parse("2026-10-29T00:12:34.567Z");
    private static final String KEY = "payment-key-1";
    private static final String REFUND_KEY = "refund-key-1";
    private static final String REFUND_REASON = "단순 변심";

    private PaymentRepository paymentRepository;
    private PaymentHistoryRepository paymentHistoryRepository;
    private MerchantRepository merchantRepository;
    private WalletClient walletClient;
    private LedgerClient ledgerClient;
    private RefundRepository refundRepository;
    private PaymentService paymentService;
    private List<Refund> savedRefunds;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentHistoryRepository = mock(PaymentHistoryRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        walletClient = mock(WalletClient.class);
        ledgerClient = mock(LedgerClient.class);
        refundRepository = mock(RefundRepository.class);
        paymentService = new PaymentService(paymentRepository, paymentHistoryRepository, merchantRepository,
                walletClient, ledgerClient, refundRepository, TransactionOperations.withoutTransaction());

        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 환불 id는 저장 시 채워지므로 테스트에서는 저장할 때 넣고, 결제별 환불 목록으로 돌려줍니다.
        savedRefunds = new ArrayList<>();
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY)).thenReturn(Optional.empty());
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            if (refund.getId() == null) {
                ReflectionTestUtils.setField(refund, "id", REFUND_ID);
            }
            if (!savedRefunds.contains(refund)) {
                savedRefunds.add(refund);
            }
            return refund;
        });
        when(refundRepository.findAllByPaymentId(PAYMENT_ID)).thenAnswer(invocation -> List.copyOf(savedRefunds));
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
    void sendsStoredWalletAndPaymentReferenceToLedger() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));

        paymentService.paymentRequest(command(10_000L));

        ArgumentCaptor<LedgerTransactionCommand> captor = ArgumentCaptor.forClass(LedgerTransactionCommand.class);
        verify(ledgerClient).createTransaction(captor.capture());
        LedgerTransactionCommand sent = captor.getValue();
        // 재시도해도 같은 요청이 되도록 지갑 조회 결과가 아닌 결제에 저장된 지갑 ID를 보냅니다.
        assertEquals(WALLET_ID, sent.debitAccountId());
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

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.paymentRequest(command(2_000_000L)));

        assertEquals(PaymentErrorCode.DECLINED, exception.getErrorCode());
        assertEquals("잔액이 부족합니다.", exception.getDeclineReason());

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
    void throwsWhenWalletNotFound() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        when(walletClient.findWallet(USER_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.WALLET_NOT_FOUND, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenWalletIsNotActive() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        when(walletClient.findWallet(USER_ID))
                .thenReturn(Optional.of(new Wallet(WALLET_ID, "SUSPENDED")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.WALLET_NOT_ACTIVE, exception.getErrorCode());
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

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.paymentRequest(command(2_000_000L)));

        assertEquals(PaymentErrorCode.DECLINED, exception.getErrorCode());
        assertEquals("잔액이 부족합니다.", exception.getDeclineReason());
        verifyNoInteractions(merchantRepository, walletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void settlesExistingPendingPaymentWhenSameKeyIsRequestedConcurrently() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        Payment pending = paymentWithId(existingPayment(10_000L));
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.empty(), Optional.of(pending));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_payments_user_id_idempotency_key"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals(10_000L, result.amount());
        verify(ledgerClient, times(1)).createTransaction(any());
    }

    @Test
    void approvesPendingPaymentWhenSameKeyIsRequestedAgain() {
        Payment pending = paymentWithId(existingPayment(10_000L));
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(pending));
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals(LEDGER_TRANSACTION_ID, pending.getLedgerId());
        ArgumentCaptor<LedgerTransactionCommand> captor = ArgumentCaptor.forClass(LedgerTransactionCommand.class);
        verify(ledgerClient, times(1)).createTransaction(captor.capture());
        assertEquals(PAYMENT_ID, captor.getValue().referenceId());
        verify(paymentRepository, times(1)).save(pending);
        List<PaymentHistory> histories = savedHistories(1);
        assertEquals(PaymentStatus.PENDING, histories.get(0).getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, histories.get(0).getCurrentStatus());
        verifyNoInteractions(merchantRepository, walletClient);
    }

    @Test
    void throwsDeclinedWhenPendingPaymentIsDeclinedOnSameKeyRequest() {
        Payment pending = paymentWithId(existingPayment(10_000L));
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(pending));
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.declined("잔액이 부족합니다."));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.DECLINED, exception.getErrorCode());
        assertEquals("잔액이 부족합니다.", exception.getDeclineReason());
        assertEquals(PaymentStatus.DECLINED, pending.getStatus());
        verify(paymentRepository, times(1)).save(pending);
        savedHistories(1);
    }

    @Test
    void keepsPendingWhenLedgerResultIsStillUnknownOnSameKeyRequest() {
        Payment pending = paymentWithId(existingPayment(10_000L));
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(pending));
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.unknown());

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.PENDING, result.status());
        verify(ledgerClient, times(1)).createTransaction(any());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(paymentHistoryRepository);
    }

    @Test
    void returnsSettledPaymentWhenAnotherRequestSettledItFirst() {
        Payment pending = paymentWithId(existingPayment(10_000L));
        Payment settledByOther = approvedPayment();
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(pending));
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(settledByOther));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals(PAYMENT_ID, result.paymentId());
    }

    @Test
    void throwsKeyReusedWhenConcurrentSameKeyRequestDiffers() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.empty(), Optional.of(existingPayment(50_000L)));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_payments_user_id_idempotency_key"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void returnsRefundsWhenReplayingRefundedPayment() {
        Payment refunded = refundPendingPayment();
        refunded.refundedHistory();
        Refund refund = existingRefund();
        refund.complete(REFUND_TRANSACTION_ID);
        savedRefunds.add(refund);
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(refunded));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(1, result.refunds().size());
        assertEquals(REFUND_ID, result.refunds().get(0).refundId());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        verifyNoInteractions(merchantRepository, walletClient, ledgerClient, paymentHistoryRepository);
    }

    private PaymentCommand command(long amount) {
        return new PaymentCommand(USER_ID, KEY, MERCHANT_ID, CurrencyCode.KRW, amount, "테스트 주문");
    }

    private Payment existingPayment(long amount) {
        return Payment.create(USER_ID, WALLET_ID, KEY, MERCHANT_ID, "테스트 가맹점", "테스트 주문",
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
        when(walletClient.findWallet(USER_ID))
                .thenReturn(Optional.of(new Wallet(WALLET_ID, "ACTIVE")));
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
        assertTrue(result.refunds().isEmpty());
    }

    @Test
    void returnsOwnPaymentWithRefunds() {
        Payment payment = refundPendingPayment();
        payment.refundedHistory();
        Refund refund = existingRefund();
        refund.complete(REFUND_TRANSACTION_ID);
        savedRefunds.add(refund);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PaymentResult result = paymentService.getPayment(USER_ID, PAYMENT_ID);

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(1, result.refunds().size());
        assertEquals(REFUND_ID, result.refunds().get(0).refundId());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        assertEquals(10_000L, result.refunds().get(0).amount());
        assertEquals(REFUND_REASON, result.refunds().get(0).reason());
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

    // ---------- 환불 요청 ----------

    @Test
    void refundsPaymentWhenLedgerApproves() {
        Payment payment = approvedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(1, result.refunds().size());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        assertEquals(10_000L, result.refunds().get(0).amount());
        assertEquals(REFUND_REASON, result.refunds().get(0).reason());

        List<PaymentHistory> histories = savedHistories(2);
        assertEquals(PaymentStatus.APPROVED, histories.get(0).getPreviousStatus());
        assertEquals(PaymentStatus.REFUND_PENDING, histories.get(0).getCurrentStatus());
        assertEquals(REFUND_REASON, histories.get(0).getReason());
        assertEquals(PaymentActor.USER, histories.get(0).getCreatedBy());
        assertEquals(PaymentStatus.REFUND_PENDING, histories.get(1).getPreviousStatus());
        assertEquals(PaymentStatus.REFUNDED, histories.get(1).getCurrentStatus());
        assertEquals(PaymentActor.SYSTEM, histories.get(1).getCreatedBy());
    }

    @Test
    void recordsRefundLedgerIdWhenLedgerApproves() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.approved(REFUND_TRANSACTION_ID));

        paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(REFUND_TRANSACTION_ID, savedRefunds.get(0).getLedgerId());
    }

    @Test
    void sendsOriginalTransactionAndRefundIdToLedger() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.approved(REFUND_TRANSACTION_ID));

        paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        ArgumentCaptor<LedgerRefundCommand> captor = ArgumentCaptor.forClass(LedgerRefundCommand.class);
        verify(ledgerClient).refundTransaction(captor.capture());
        LedgerRefundCommand command = captor.getValue();
        assertEquals(LEDGER_TRANSACTION_ID, command.originalTransactionId());
        assertEquals("PAYMENT_REFUND", command.referenceType());
        assertEquals(REFUND_ID, command.referenceId());
        assertEquals(10_000L, command.amount());
        assertEquals(CurrencyCode.KRW, command.currency());
    }

    @Test
    void keepsRefundPendingWhenLedgerResultIsUnknown() {
        Payment payment = approvedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.unknown());

        PaymentResult result = paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUND_PENDING, result.status());
        assertEquals(RefundStatus.REQUESTED, result.refunds().get(0).status());
        savedHistories(1);
    }

    @Test
    void returnsToApprovedAndThrowsDeclinedWhenLedgerDeclines() {
        Payment payment = approvedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.declined("원거래를 찾을 수 없습니다."));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_DECLINED, exception.getErrorCode());
        assertEquals("원거래를 찾을 수 없습니다.", exception.getDeclineReason());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(RefundStatus.DECLINED, savedRefunds.get(0).getStatus());
        assertEquals("원거래를 찾을 수 없습니다.", savedRefunds.get(0).getDeclineReason());

        List<PaymentHistory> histories = savedHistories(2);
        assertEquals(PaymentStatus.REFUND_PENDING, histories.get(1).getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, histories.get(1).getCurrentStatus());
        assertEquals("원거래를 찾을 수 없습니다.", histories.get(1).getReason());
        assertEquals(PaymentActor.SYSTEM, histories.get(1).getCreatedBy());
    }

    @Test
    void returnsResultForSameKeyOfCompletedRefund() {
        Payment payment = refundPendingPayment();
        payment.refundedHistory();
        Refund existing = existingRefund();
        existing.complete(REFUND_TRANSACTION_ID);
        savedRefunds.add(existing);
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PaymentResult result = paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
        verify(refundRepository, never()).save(any());
    }

    @Test
    void throwsDeclinedAgainForSameKeyOfDeclinedRefund() {
        Refund existing = existingRefund();
        existing.decline("원거래를 찾을 수 없습니다.");
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_DECLINED, exception.getErrorCode());
        assertEquals("원거래를 찾을 수 없습니다.", exception.getDeclineReason());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void keepsRefundPendingWhenLedgerResultIsStillUnknownOnSameKeyRequest() {
        Refund existing = existingRefund();
        savedRefunds.add(existing);
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundPendingPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.unknown());

        PaymentResult result = paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUND_PENDING, result.status());
        assertEquals(RefundStatus.REQUESTED, result.refunds().get(0).status());
        verify(ledgerClient, times(1)).refundTransaction(any());
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(refundRepository, never()).save(any());
        verifyNoInteractions(paymentHistoryRepository);
    }

    @Test
    void completesRequestedRefundWhenSameKeyIsRequestedAgain() {
        Refund existing = existingRefund();
        savedRefunds.add(existing);
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundPendingPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        assertEquals(REFUND_TRANSACTION_ID, existing.getLedgerId());
        ArgumentCaptor<LedgerRefundCommand> captor = ArgumentCaptor.forClass(LedgerRefundCommand.class);
        verify(ledgerClient, times(1)).refundTransaction(captor.capture());
        assertEquals(REFUND_ID, captor.getValue().referenceId());
        List<PaymentHistory> histories = savedHistories(1);
        assertEquals(PaymentStatus.REFUND_PENDING, histories.get(0).getPreviousStatus());
        assertEquals(PaymentStatus.REFUNDED, histories.get(0).getCurrentStatus());
    }

    @Test
    void throwsDeclinedWhenRequestedRefundIsDeclinedOnSameKeyRequest() {
        Refund existing = existingRefund();
        Payment payment = refundPendingPayment();
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.declined("원거래를 찾을 수 없습니다."));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_DECLINED, exception.getErrorCode());
        assertEquals("원거래를 찾을 수 없습니다.", exception.getDeclineReason());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(RefundStatus.DECLINED, existing.getStatus());
        savedHistories(1);
    }

    @Test
    void returnsSettledRefundWhenAnotherRequestSettledItFirst() {
        Refund existing = existingRefund();
        Refund completedByOther = existingRefund();
        completedByOther.complete(REFUND_TRANSACTION_ID);
        Payment refundedByOther = refundPendingPayment();
        refundedByOther.refundedHistory();
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY))
                .thenReturn(Optional.of(existing), Optional.of(completedByOther));
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(refundPendingPayment()), Optional.of(refundedByOther));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.approved(REFUND_TRANSACTION_ID));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));
        when(refundRepository.findAllByPaymentId(PAYMENT_ID)).thenReturn(List.of(completedByOther));

        PaymentResult result = paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
    }

    @Test
    void throwsWhenRefundKeyReusedForOtherPayment() {
        Payment otherPayment = existingPayment(10_000L);
        ReflectionTestUtils.setField(otherPayment, "id", OTHER_PAYMENT_ID);
        otherPayment.approvedHistory(LEDGER_TRANSACTION_ID);
        when(paymentRepository.findById(OTHER_PAYMENT_ID)).thenReturn(Optional.of(otherPayment));
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY))
                .thenReturn(Optional.of(existingRefund()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, OTHER_PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsNotFoundForMissingPaymentEvenWithUsedRefundKey() {
        when(paymentRepository.findById(OTHER_PAYMENT_ID)).thenReturn(Optional.empty());
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY))
                .thenReturn(Optional.of(existingRefund()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, OTHER_PAYMENT_ID)));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsRefundInProgressForNewKeyWhileRefundPending() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundPendingPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
        verify(refundRepository, never()).save(any());
    }

    @Test
    void throwsAlreadyRefundedForRefundedPayment() {
        Payment payment = refundPendingPayment();
        payment.refundedHistory();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.ALREADY_REFUNDED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsNotRefundableForPendingPayment() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(paymentWithId(existingPayment(10_000L))));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsNotFoundWhenRefundingOtherUsersPayment() {
        when(refundRepository.findByUserIdAndIdempotencyKey(OTHER_USER_ID, REFUND_KEY)).thenReturn(Optional.empty());
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OTHER_USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsRefundInProgressWhenConcurrentRefundWonFirst() {
        // 요청 시작 확인, 트랜잭션 안 조회, 충돌 후 재조회 순서로 결제를 읽습니다.
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(approvedPayment()), Optional.of(approvedPayment()),
                        Optional.of(refundPendingPayment()));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void settlesExistingRequestedRefundWhenSameKeyIsRequestedConcurrently() {
        Refund existing = existingRefund();
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(approvedPayment()), Optional.of(approvedPayment()),
                        Optional.of(refundPendingPayment()));
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(refundRepository.save(any(Refund.class)))
                .thenThrow(new DataIntegrityViolationException("uk_refunds_user_id_idempotency_key"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.findAllByPaymentId(PAYMENT_ID)).thenReturn(List.of(existing));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(REFUND_ID, result.refunds().get(0).refundId());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        verify(ledgerClient, times(1)).refundTransaction(any());
    }

    @Test
    void throwsKeyReusedWhenSameKeyIsUsedConcurrentlyForOtherPayment() {
        Refund otherPaymentRefund = Refund.request(
                OTHER_PAYMENT_ID, USER_ID, REFUND_KEY, 10_000L, CurrencyCode.KRW, REFUND_REASON);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY))
                .thenReturn(Optional.empty(), Optional.of(otherPaymentRefund));
        when(refundRepository.save(any(Refund.class)))
                .thenThrow(new DataIntegrityViolationException("uk_refunds_user_id_idempotency_key"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void rethrowsConstraintViolationWhenNoRefundHasSameKey() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        when(refundRepository.save(any(Refund.class)))
                .thenThrow(new DataIntegrityViolationException("ck_refunds_amount"));

        assertThrows(DataIntegrityViolationException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));
        verifyNoInteractions(ledgerClient);
    }

    private RefundCommand refundCommand(UUID userId, UUID paymentId) {
        return new RefundCommand(userId, paymentId, REFUND_KEY, REFUND_REASON);
    }

    // id는 저장 시 채워지므로 테스트에서는 직접 넣습니다.
    private Payment paymentWithId(Payment payment) {
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private Payment approvedPayment() {
        Payment payment = paymentWithId(existingPayment(10_000L));
        payment.approvedHistory(LEDGER_TRANSACTION_ID);
        return payment;
    }

    private Payment refundPendingPayment() {
        Payment payment = approvedPayment();
        payment.refundRequestedHistory(REFUND_REASON);
        return payment;
    }

    private Refund existingRefund() {
        Refund refund = Refund.request(PAYMENT_ID, USER_ID, REFUND_KEY, 10_000L, CurrencyCode.KRW, REFUND_REASON);
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
        return refund;
    }

    // ---------- 목록 조회 ----------

    @Test
    void requestsLatestFirstPageWithFixedSort() {
        when(paymentRepository.findPageByUserId(any(), any(), any())).thenReturn(Page.empty());

        paymentService.getPayments(USER_ID, null, 0, 20);

        Pageable pageable = queriedPageable(null);
        assertEquals(0, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")), pageable.getSort());
    }

    @Test
    void returnsPaymentSummariesWithPageInfo() {
        Payment newer = savedPayment(PAYMENT_ID, CREATED_AT);
        Payment older = savedPayment(OLDER_PAYMENT_ID, CREATED_AT.minusSeconds(60));
        when(paymentRepository.findPageByUserId(any(), any(), any()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(newer, older), invocation.getArgument(2), 35));

        Page<PaymentListResult> result = paymentService.getPayments(USER_ID, null, 0, 2);

        assertEquals(2, result.getContent().size());
        PaymentListResult first = result.getContent().get(0);
        assertEquals(PAYMENT_ID, first.paymentId());
        assertEquals(PaymentStatus.PENDING, first.status());
        assertEquals(10_000L, first.amount());
        assertEquals(CurrencyCode.KRW, first.currency());
        assertEquals("테스트 가맹점", first.merchantName());
        assertEquals(CREATED_AT, first.createdAt());
        assertEquals(35, result.getTotalElements());
        assertEquals(18, result.getTotalPages());
    }

    @Test
    void passesStatusFilterToRepository() {
        when(paymentRepository.findPageByUserId(any(), any(), any())).thenReturn(Page.empty());

        paymentService.getPayments(USER_ID, PaymentStatus.APPROVED, 0, 20);

        queriedPageable(PaymentStatus.APPROVED);
    }

    @Test
    void limitsSizeToMaximum() {
        when(paymentRepository.findPageByUserId(any(), any(), any())).thenReturn(Page.empty());

        paymentService.getPayments(USER_ID, null, 0, 1_000);

        assertEquals(100, queriedPageable(null).getPageSize());
    }

    @Test
    void throwsWhenSizeIsNotPositive() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getPayments(USER_ID, null, 0, 0));

        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verify(paymentRepository, never()).findPageByUserId(any(), any(), any());
    }

    @Test
    void throwsWhenPageIsNegative() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getPayments(USER_ID, null, -1, 20));

        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verify(paymentRepository, never()).findPageByUserId(any(), any(), any());
    }

    private Pageable queriedPageable(PaymentStatus status) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findPageByUserId(eq(USER_ID), eq(status), captor.capture());
        return captor.getValue();
    }

    // id·createdAt은 저장 시 채워지므로 테스트에서는 직접 넣습니다.
    private Payment savedPayment(UUID id, Instant createdAt) {
        Payment payment = existingPayment(10_000L);
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "createdAt", createdAt);
        return payment;
    }

    // ---------- 복구 배치 ----------

    private static final Instant STALE_BEFORE = Instant.parse("2026-10-30T01:00:00Z");

    @Test
    void settlesStalePendingPayments() {
        Payment first = savedPayment(PAYMENT_ID, CREATED_AT);
        Payment second = savedPayment(OTHER_PAYMENT_ID, CREATED_AT);
        when(paymentRepository.findStalePending(STALE_BEFORE, 100)).thenReturn(List.of(first, second));
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));

        paymentService.settleStalePayments(STALE_BEFORE, 100);

        assertEquals(PaymentStatus.APPROVED, first.getStatus());
        assertEquals(PaymentStatus.APPROVED, second.getStatus());
        verify(ledgerClient, times(2)).createTransaction(any());
    }

    @Test
    void continuesPaymentRecoveryWhenOnePaymentFails() {
        Payment first = savedPayment(PAYMENT_ID, CREATED_AT);
        Payment second = savedPayment(OTHER_PAYMENT_ID, CREATED_AT);
        when(paymentRepository.findStalePending(STALE_BEFORE, 100)).thenReturn(List.of(first, second));
        when(ledgerClient.createTransaction(any()))
                .thenThrow(new IllegalStateException("원장 연결 실패"))
                .thenReturn(LedgerResult.approved(LEDGER_TRANSACTION_ID));

        paymentService.settleStalePayments(STALE_BEFORE, 100);

        assertEquals(PaymentStatus.PENDING, first.getStatus());
        assertEquals(PaymentStatus.APPROVED, second.getStatus());
    }

    @Test
    void stopsPaymentRecoveryWhenLedgerKeepsReturningUnknown() {
        List<Payment> pendings = List.of(
                savedPayment(UUID.randomUUID(), CREATED_AT), savedPayment(UUID.randomUUID(), CREATED_AT),
                savedPayment(UUID.randomUUID(), CREATED_AT), savedPayment(UUID.randomUUID(), CREATED_AT));
        when(paymentRepository.findStalePending(STALE_BEFORE, 100)).thenReturn(pendings);
        when(ledgerClient.createTransaction(any())).thenReturn(LedgerResult.unknown());

        paymentService.settleStalePayments(STALE_BEFORE, 100);

        verify(ledgerClient, times(3)).createTransaction(any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void settlesStaleRequestedRefunds() {
        Refund refund = existingRefund();
        Payment payment = refundPendingPayment();
        when(refundRepository.findStaleRequested(STALE_BEFORE, 100)).thenReturn(List.of(refund));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.approved(REFUND_TRANSACTION_ID));

        paymentService.settleStaleRefunds(STALE_BEFORE, 100);

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void returnsPaymentToApprovedWithoutThrowingWhenStaleRefundIsDeclined() {
        Refund refund = existingRefund();
        Payment payment = refundPendingPayment();
        when(refundRepository.findStaleRequested(STALE_BEFORE, 100)).thenReturn(List.of(refund));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.declined("원거래를 찾을 수 없습니다."));

        paymentService.settleStaleRefunds(STALE_BEFORE, 100);

        assertEquals(RefundStatus.DECLINED, refund.getStatus());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
    }

    @Test
    void stopsRefundRecoveryWhenLedgerKeepsReturningUnknown() {
        List<Refund> requested = List.of(existingRefund(), existingRefund(), existingRefund(), existingRefund());
        when(refundRepository.findStaleRequested(STALE_BEFORE, 100)).thenReturn(requested);
        when(paymentRepository.findById(PAYMENT_ID)).thenAnswer(invocation -> Optional.of(refundPendingPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(LedgerResult.unknown());

        paymentService.settleStaleRefunds(STALE_BEFORE, 100);

        verify(ledgerClient, times(3)).refundTransaction(any());
        verify(refundRepository, never()).save(any());
    }
}

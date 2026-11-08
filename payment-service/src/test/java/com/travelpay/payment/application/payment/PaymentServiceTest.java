package com.travelpay.payment.application.payment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerResult;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerTransactionCommand;
import com.travelpay.payment.application.payment.client.wallet.PaymentWallet;
import com.travelpay.payment.application.payment.client.wallet.PaymentWalletClient;
import com.travelpay.payment.application.payment.dto.MerchantPaymentListResult;
import com.travelpay.payment.application.payment.dto.OverdueRefundRequestResult;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.PaymentListResult;
import com.travelpay.payment.application.payment.dto.PaymentResult;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import com.travelpay.payment.application.payment.dto.RefundRequestSummary;
import com.travelpay.payment.application.payment.dto.StuckSettlementResult;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.merchant.MerchantStatus;
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
import com.travelpay.payment.domain.payment.SettlementReviewReason;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OTHER_USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID REFUND_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID REFUND_TRANSACTION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_PAYMENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID OLDER_PAYMENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID MERCHANT_WALLET_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Instant CREATED_AT = Instant.parse("2026-10-29T00:12:34.567Z");
    private static final String KEY = "payment-key-1";
    private static final String REFUND_KEY = "refund-key-1";
    private static final String REFUND_REASON = "단순 변심";

    private PaymentRepository paymentRepository;
    private PaymentHistoryRepository paymentHistoryRepository;
    private MerchantRepository merchantRepository;
    private PaymentWalletClient paymentWalletClient;
    private PaymentLedgerClient ledgerClient;
    private RefundRepository refundRepository;
    private PaymentService paymentService;
    private List<Refund> savedRefunds;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentHistoryRepository = mock(PaymentHistoryRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        paymentWalletClient = mock(PaymentWalletClient.class);
        ledgerClient = mock(PaymentLedgerClient.class);
        refundRepository = mock(RefundRepository.class);
        paymentService = new PaymentService(paymentRepository, paymentHistoryRepository, merchantRepository,
                paymentWalletClient, ledgerClient, refundRepository, TransactionOperations.withoutTransaction());

        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 원장 거래에 넣을 가맹점 원장 지갑. 가맹점 검증 테스트는 각자 다시 지정합니다.
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);

        // 환불 id는 저장 시 채워지므로 테스트에서는 저장할 때 넣고, 결제별 환불 목록으로 돌려줍니다.
        savedRefunds = new ArrayList<>();
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY)).thenReturn(Optional.empty());
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

        // 환불 기간 검사는 결제 승인 이력 시각을 봅니다. 기본은 하루 전 승인으로 기간 안입니다.
        when(paymentHistoryRepository.findApprovedAt(PAYMENT_ID))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(1))));
    }

    @Test
    void approvesPaymentWhenLedgerApproves() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));

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
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));

        paymentService.paymentRequest(command(10_000L));

        ArgumentCaptor<PaymentLedgerTransactionCommand> captor = ArgumentCaptor.forClass(PaymentLedgerTransactionCommand.class);
        verify(ledgerClient).createTransaction(captor.capture());
        PaymentLedgerTransactionCommand sent = captor.getValue();
        // 재시도해도 같은 요청이 되도록 지갑 조회 결과가 아닌 결제에 저장된 지갑 ID를 보냅니다.
        assertEquals(WALLET_ID, sent.userWalletId());
        assertEquals(MERCHANT_WALLET_ID, sent.merchantWalletId());
        assertEquals(USER_ID, sent.userId());
        assertEquals(CurrencyCode.KRW, sent.currency());
        assertEquals(10_000L, sent.amount());
    }

    @Test
    void throwsWhenMerchantHasNoLedgerWallet() {
        Merchant merchant = givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        when(merchant.getLedgerWalletId()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_ACTIVE, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(paymentWalletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsDeclinedButKeepsDeclinedRecordWhenLedgerDeclines() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.declined("잔액이 부족합니다."));

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
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.PENDING, result.status());
        verify(paymentRepository, times(1)).save(any(Payment.class));
        List<PaymentHistory> histories = savedHistories(1);
        assertEquals(PaymentStatus.PENDING, histories.get(0).getCurrentStatus());
        // 첫 요청도 시도 1회로 셈
        verify(paymentRepository).recordUnknownAttempt(any(), eq(0), eq(PaymentStatus.PENDING), eq(null), any());
    }

    @Test
    void keepsPendingWhenRecordingUnknownFails() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.unknown());
        when(paymentRepository.recordUnknownAttempt(any(), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalStateException("DB 연결 실패"));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.PENDING, result.status());
    }

    @Test
    void marksManualReviewWhenLedgerDoesNotAcceptPayment() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.notAccepted());
        when(paymentRepository.markManualReview(any(), eq(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED), any()))
                .thenReturn(1);
        Payment manualReview = paymentWithId(existingPayment(10_000L));
        ReflectionTestUtils.setField(manualReview, "status", PaymentStatus.MANUAL_REVIEW);
        when(paymentRepository.findById(any())).thenReturn(Optional.of(manualReview));

        // 예외 없이 MANUAL_REVIEW 상태로 응답
        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.MANUAL_REVIEW, result.status());
        List<PaymentHistory> histories = savedHistories(2);
        assertEquals(PaymentStatus.PENDING, histories.get(1).getPreviousStatus());
        assertEquals(PaymentStatus.MANUAL_REVIEW, histories.get(1).getCurrentStatus());
        assertEquals("LEDGER_REQUEST_NOT_ACCEPTED", histories.get(1).getReason());
        assertEquals(PaymentActor.SYSTEM, histories.get(1).getCreatedBy());
        verify(paymentRepository, never()).recordUnknownAttempt(any(), anyInt(), any(), any(), any());
    }

    @Test
    void doesNotSaveHistoryWhenManualReviewUpdateMissed() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.notAccepted());
        when(paymentRepository.markManualReview(any(), any(), any())).thenReturn(0);

        paymentService.paymentRequest(command(10_000L));

        // 생성 이력 1건만
        savedHistories(1);
    }

    @Test
    void throwsWhenMerchantNotFound() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(paymentWalletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenMerchantNotActive() {
        givenMerchant(MerchantStatus.SUSPENDED, CurrencyCode.KRW);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_ACTIVE, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(paymentWalletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenCurrencyDiffersFromMerchant() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.JPY);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.CURRENCY_MISMATCH, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(paymentWalletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenWalletNotFound() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        when(paymentWalletClient.findWallet(USER_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(10_000L)));

        assertEquals(PaymentErrorCode.WALLET_NOT_FOUND, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenWalletIsNotActive() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        when(paymentWalletClient.findWallet(USER_ID))
                .thenReturn(Optional.of(new PaymentWallet(WALLET_ID, "SUSPENDED")));

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
        verifyNoInteractions(merchantRepository, paymentWalletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenIdempotencyKeyReusedWithDifferentRequest() {
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(existingPayment(10_000L)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.paymentRequest(command(50_000L)));

        assertEquals(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(merchantRepository, paymentWalletClient, ledgerClient, paymentHistoryRepository);
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
        verifyNoInteractions(merchantRepository, paymentWalletClient, ledgerClient, paymentHistoryRepository);
    }

    @Test
    void returnsPendingWhenSameKeyIsRequestedConcurrently() {
        givenMerchant(MerchantStatus.ACTIVE, CurrencyCode.KRW);
        givenWallet();
        Payment pending = paymentWithId(existingPayment(10_000L));
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.empty(), Optional.of(pending));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_payments_user_id_idempotency_key"));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        // 먼저 저장한 요청이 원장을 호출하므로, 뒤 요청은 원장을 다시 부르지 않고 현재 상태로 응답
        assertEquals(PaymentStatus.PENDING, result.status());
        assertEquals(10_000L, result.amount());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void returnsPendingWithoutLedgerCallWhenSameKeyIsRequestedAgain() {
        Payment pending = paymentWithId(existingPayment(10_000L));
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(pending));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.PENDING, result.status());
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(ledgerClient, paymentWalletClient, paymentHistoryRepository);
    }

    @Test
    void returnsManualReviewPaymentOnSameKeyRequest() {
        Payment manualReview = paymentWithId(existingPayment(10_000L));
        ReflectionTestUtils.setField(manualReview, "status", PaymentStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(manualReview, "reviewReason", SettlementReviewReason.RETRY_EXHAUSTED);
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(manualReview));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.MANUAL_REVIEW, result.status());
        verifyNoInteractions(ledgerClient, paymentWalletClient, paymentHistoryRepository);
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
        refunded.refundCompletedHistory(10_000L, 0L);
        Refund refund = existingRefund();
        refund.complete(REFUND_TRANSACTION_ID);
        savedRefunds.add(refund);
        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(refunded));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(1, result.refunds().size());
        assertEquals(REFUND_ID, result.refunds().get(0).refundId());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        verifyNoInteractions(merchantRepository, paymentWalletClient, ledgerClient, paymentHistoryRepository);
    }

    private PaymentCommand command(long amount) {
        return new PaymentCommand(USER_ID, KEY, MERCHANT_ID, CurrencyCode.KRW, amount, "테스트 주문");
    }

    private Payment existingPayment(long amount) {
        return Payment.create(USER_ID, WALLET_ID, KEY, MERCHANT_ID, "테스트 가맹점", "테스트 주문",
                CurrencyCode.KRW, amount);
    }

    private Merchant givenMerchant(MerchantStatus status, CurrencyCode currency) {
        Merchant merchant = mock(Merchant.class);
        when(merchant.getId()).thenReturn(MERCHANT_ID);
        when(merchant.getName()).thenReturn("테스트 가맹점");
        when(merchant.getStatus()).thenReturn(status);
        when(merchant.getCurrency()).thenReturn(currency);
        when(merchant.getLedgerWalletId()).thenReturn(MERCHANT_WALLET_ID);
        when(merchant.getOwnerUserId()).thenReturn(OWNER_ID);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        return merchant;
    }

    private void givenWallet() {
        when(paymentWalletClient.findWallet(USER_ID))
                .thenReturn(Optional.of(new PaymentWallet(WALLET_ID, "ACTIVE")));
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
        payment.refundCompletedHistory(10_000L, 0L);
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
        Payment payment = refundRequestedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(1, result.refunds().size());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        assertEquals(10_000L, result.refunds().get(0).amount());
        assertEquals(REFUND_REASON, result.refunds().get(0).reason());

        List<PaymentHistory> histories = savedHistories(2);
        assertEquals(PaymentStatus.REFUND_REQUESTED, histories.get(0).getPreviousStatus());
        assertEquals(PaymentStatus.REFUND_PENDING, histories.get(0).getCurrentStatus());
        assertEquals(REFUND_REASON, histories.get(0).getReason());
        assertEquals(PaymentActor.MERCHANT, histories.get(0).getCreatedBy());
        assertEquals(PaymentStatus.REFUND_PENDING, histories.get(1).getPreviousStatus());
        assertEquals(PaymentStatus.REFUNDED, histories.get(1).getCurrentStatus());
        assertEquals(PaymentActor.SYSTEM, histories.get(1).getCreatedBy());
    }

    @Test
    void recordsRefundLedgerIdWhenLedgerApproves() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        assertEquals(REFUND_TRANSACTION_ID, savedRefunds.get(0).getLedgerId());
    }

    @Test
    void sendsOriginalTransactionAndRefundIdToLedger() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        ArgumentCaptor<PaymentLedgerRefundCommand> captor = ArgumentCaptor.forClass(PaymentLedgerRefundCommand.class);
        verify(ledgerClient).refundTransaction(captor.capture());
        PaymentLedgerRefundCommand command = captor.getValue();
        assertEquals(LEDGER_TRANSACTION_ID, command.originalTransactionId());
        assertEquals(REFUND_ID, command.referenceId());
        assertEquals(USER_ID, command.userId());
        assertEquals(WALLET_ID, command.userWalletId());
        assertEquals(MERCHANT_WALLET_ID, command.merchantWalletId());
        assertEquals(10_000L, command.amount());
        assertEquals(CurrencyCode.KRW, command.currency());
    }

    @Test
    void keepsRefundPendingWhenLedgerResultIsUnknown() {
        Payment payment = refundRequestedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUND_PENDING, result.status());
        assertEquals(RefundStatus.REQUESTED, result.refunds().get(0).status());
        savedHistories(1);
        verify(refundRepository).recordUnknownAttempt(eq(REFUND_ID), eq(0), eq(RefundStatus.REQUESTED), eq(null), any());
    }

    @Test
    void marksRefundManualReviewWhenLedgerDoesNotAcceptRefund() {
        Payment payment = refundRequestedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.notAccepted());
        when(refundRepository.markManualReview(eq(REFUND_ID), eq(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED), any()))
                .thenAnswer(invocation -> {
                    ReflectionTestUtils.setField(savedRefunds.get(0), "status", RefundStatus.MANUAL_REVIEW);
                    return 1;
                });

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        // 돈이 나갔는지 모르므로 결제는 REFUND_PENDING 유지, 결제 상태가 그대로라 추가 이력 없음
        assertEquals(PaymentStatus.REFUND_PENDING, result.status());
        assertEquals(RefundStatus.MANUAL_REVIEW, result.refunds().get(0).status());
        savedHistories(1);
    }

    @Test
    void returnsToApprovedAndThrowsDeclinedWhenLedgerDeclines() {
        Payment payment = refundRequestedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.declined("원거래를 찾을 수 없습니다."));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

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
        payment.refundCompletedHistory(10_000L, 0L);
        Refund existing = existingRefund();
        existing.complete(REFUND_TRANSACTION_ID);
        savedRefunds.add(existing);
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
        verify(refundRepository, never()).save(any());
    }

    @Test
    void throwsDeclinedAgainForSameKeyOfDeclinedRefund() {
        Refund existing = existingRefund();
        existing.decline("원거래를 찾을 수 없습니다.");
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_DECLINED, exception.getErrorCode());
        assertEquals("원거래를 찾을 수 없습니다.", exception.getDeclineReason());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void returnsRequestedRefundWithoutLedgerCallOnSameKeyRequest() {
        Refund existing = existingRefund();
        savedRefunds.add(existing);
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundPendingPayment()));

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUND_PENDING, result.status());
        assertEquals(RefundStatus.REQUESTED, result.refunds().get(0).status());
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(refundRepository, never()).save(any());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsWhenRefundKeyReusedForOtherPayment() {
        Payment otherPayment = existingPayment(10_000L);
        ReflectionTestUtils.setField(otherPayment, "id", OTHER_PAYMENT_ID);
        otherPayment.approvedHistory(LEDGER_TRANSACTION_ID);
        when(paymentRepository.findById(OTHER_PAYMENT_ID)).thenReturn(Optional.of(otherPayment));
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY))
                .thenReturn(Optional.of(existingRefund()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, OTHER_PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsNotFoundForMissingPaymentEvenWithUsedRefundKey() {
        when(paymentRepository.findById(OTHER_PAYMENT_ID)).thenReturn(Optional.empty());
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY))
                .thenReturn(Optional.of(existingRefund()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, OTHER_PAYMENT_ID)));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsRefundInProgressForNewKeyWhileRefundPending() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundPendingPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
        verify(refundRepository, never()).save(any());
    }

    // 환불 기간은 고객 신청 시점에만 확인합니다.
    @Test
    void customerRequestsRefundJustBeforeRefundPeriodEnds() {
        when(paymentHistoryRepository.findApprovedAt(PAYMENT_ID))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(3)).plus(Duration.ofMinutes(1))));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        PaymentResult result = paymentService.requestRefund(USER_ID, PAYMENT_ID, REFUND_REASON);

        assertEquals(PaymentStatus.REFUND_REQUESTED, result.status());
    }

    // 기간 안에 들어온 신청은 가맹점 처리가 늦어 기간이 지나도 실행할 수 있습니다.
    @Test
    void merchantRefundsRequestedPaymentEvenAfterRefundPeriod() {
        when(paymentHistoryRepository.findApprovedAt(PAYMENT_ID))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(3)).minus(Duration.ofMinutes(1))));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
    }

    @Test
    void masterRefundsApprovedPaymentWithoutPeriodLimit() {
        when(paymentHistoryRepository.findApprovedAt(PAYMENT_ID))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(10))));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(
                refundCommand(OTHER_USER_ID, AuthRole.MASTER, PAYMENT_ID, 10_000L));

        assertEquals(PaymentStatus.REFUNDED, result.status());
    }

    @Test
    void throwsNotRequestedWhenMerchantRefundsWithoutCustomerRequest() {
        Payment payment = approvedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_NOT_REQUESTED, exception.getErrorCode());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        verifyNoInteractions(ledgerClient);
        verify(refundRepository, never()).save(any());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void returnsOriginalResultForSameKeyEvenAfterRefundPeriod() {
        when(paymentHistoryRepository.findApprovedAt(PAYMENT_ID))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(4))));
        Payment payment = refundPendingPayment();
        payment.refundCompletedHistory(10_000L, 0L);
        Refund existing = existingRefund();
        existing.complete(REFUND_TRANSACTION_ID);
        savedRefunds.add(existing);
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
    }

    @Test
    void throwsNotRefundableWhenRequestingRefundWithoutApprovalHistory() {
        when(paymentHistoryRepository.findApprovedAt(PAYMENT_ID)).thenReturn(Optional.empty());
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.requestRefund(USER_ID, PAYMENT_ID, REFUND_REASON));

        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, exception.getErrorCode());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void throwsAlreadyRefundedForRefundedPayment() {
        Payment payment = refundPendingPayment();
        payment.refundCompletedHistory(10_000L, 0L);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.ALREADY_REFUNDED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsNotRefundableForPendingPayment() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(paymentWithId(existingPayment(10_000L))));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsNotFoundWhenRefundingOtherUsersPayment() {
        when(refundRepository.findByUserIdAndIdempotencyKey(OTHER_USER_ID, REFUND_KEY)).thenReturn(Optional.empty());
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OTHER_USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository);
    }

    @Test
    void throwsRefundInProgressWhenConcurrentRefundWonFirst() {
        // 요청 시작 확인, 트랜잭션 안 조회, 충돌 후 재조회 순서로 결제를 읽습니다.
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(refundRequestedPayment()), Optional.of(refundRequestedPayment()),
                        Optional.of(refundPendingPayment()));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void returnsRequestedRefundWhenSameKeyIsRequestedConcurrently() {
        Refund existing = existingRefund();
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(refundRequestedPayment()), Optional.of(refundRequestedPayment()),
                        Optional.of(refundPendingPayment()));
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(refundRepository.save(any(Refund.class)))
                .thenThrow(new DataIntegrityViolationException("uk_refunds_user_id_idempotency_key"));
        // 남은 금액 계산 시점에는 동시 요청의 환불이 아직 보이지 않습니다.
        when(refundRepository.findAllByPaymentId(PAYMENT_ID)).thenReturn(List.of(), List.of(existing));

        PaymentResult result = paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID));

        // 먼저 저장한 요청이 원장을 호출하므로, 뒤 요청은 원장을 다시 부르지 않고 현재 상태로 응답
        assertEquals(PaymentStatus.REFUND_PENDING, result.status());
        assertEquals(REFUND_ID, result.refunds().get(0).refundId());
        assertEquals(RefundStatus.REQUESTED, result.refunds().get(0).status());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void throwsKeyReusedWhenSameKeyIsUsedConcurrentlyForOtherPayment() {
        Refund otherPaymentRefund = Refund.request(
                OTHER_PAYMENT_ID, OWNER_ID, REFUND_KEY, 10_000L, CurrencyCode.KRW, REFUND_REASON);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY))
                .thenReturn(Optional.empty(), Optional.of(otherPaymentRefund));
        when(refundRepository.save(any(Refund.class)))
                .thenThrow(new DataIntegrityViolationException("uk_refunds_user_id_idempotency_key"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void rethrowsConstraintViolationWhenNoRefundHasSameKey() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(refundRepository.save(any(Refund.class)))
                .thenThrow(new DataIntegrityViolationException("ck_refunds_amount"));

        assertThrows(DataIntegrityViolationException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, PAYMENT_ID)));
        verifyNoInteractions(ledgerClient);
    }

    // ---------- 가맹점 환불 권한 ----------

    @Test
    void masterCanRefundPaymentOfAnyMerchant() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(
                refundCommand(OTHER_USER_ID, AuthRole.MASTER, PAYMENT_ID, 10_000L));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(PaymentActor.MASTER, savedHistories(2).get(0).getCreatedBy());
    }

    @Test
    void throwsNotFoundWhenCustomerRequestsRefund() {
        when(refundRepository.findByUserIdAndIdempotencyKey(USER_ID, REFUND_KEY)).thenReturn(Optional.empty());
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(USER_ID, PAYMENT_ID)));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    // ---------- 부분 환불 ----------

    @Test
    void partialRefundReturnsPaymentToApproved() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(
                refundCommand(OWNER_ID, AuthRole.USER, PAYMENT_ID, 3_000L));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertEquals(3_000L, result.refunds().get(0).amount());
        assertEquals(RefundStatus.COMPLETED, result.refunds().get(0).status());
        ArgumentCaptor<PaymentLedgerRefundCommand> captor = ArgumentCaptor.forClass(PaymentLedgerRefundCommand.class);
        verify(ledgerClient).refundTransaction(captor.capture());
        assertEquals(3_000L, captor.getValue().amount());
        List<PaymentHistory> histories = savedHistories(2);
        assertEquals(PaymentStatus.REFUND_PENDING, histories.get(1).getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, histories.get(1).getCurrentStatus());
        assertEquals("부분 환불 완료: 3000", histories.get(1).getReason());
    }

    @Test
    void refundsRemainingAmountAfterPartialRefund() {
        savedRefunds.add(earlierRefund(3_000L, RefundStatus.COMPLETED));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(
                refundCommand(OWNER_ID, AuthRole.USER, PAYMENT_ID, 7_000L));

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(2, result.refunds().size());
    }

    @Test
    void throwsAmountExceededWhenRequestIsOverRemainingAmount() {
        savedRefunds.add(earlierRefund(3_000L, RefundStatus.COMPLETED));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, AuthRole.USER, PAYMENT_ID, 8_000L)));

        assertEquals(PaymentErrorCode.REFUND_AMOUNT_EXCEEDED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(refundRepository, never()).save(any());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void declinedRefundDoesNotReduceRemainingAmount() {
        savedRefunds.add(earlierRefund(3_000L, RefundStatus.DECLINED));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        PaymentResult result = paymentService.refundRequest(
                refundCommand(OWNER_ID, AuthRole.USER, PAYMENT_ID, 10_000L));

        assertEquals(PaymentStatus.REFUNDED, result.status());
    }

    @Test
    void throwsKeyReusedWhenSameKeyIsSentWithDifferentAmount() {
        Refund existing = existingRefund();
        existing.complete(REFUND_TRANSACTION_ID);
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY)).thenReturn(Optional.of(existing));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.refundRequest(refundCommand(OWNER_ID, AuthRole.USER, PAYMENT_ID, 3_000L)));

        assertEquals(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    private RefundCommand refundCommand(UUID userId, UUID paymentId) {
        return refundCommand(userId, AuthRole.USER, paymentId, 10_000L);
    }

    private RefundCommand refundCommand(UUID userId, AuthRole role, UUID paymentId, long amount) {
        return new RefundCommand(userId, role, paymentId, REFUND_KEY, amount, REFUND_REASON);
    }

    // 이번 요청 전에 끝난 환불 (다른 id와 키)
    private Refund earlierRefund(long amount, RefundStatus status) {
        Refund refund = Refund.request(PAYMENT_ID, OWNER_ID, "earlier-key", amount, CurrencyCode.KRW, REFUND_REASON);
        ReflectionTestUtils.setField(refund, "id", UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"));
        if (status == RefundStatus.COMPLETED) {
            refund.complete(REFUND_TRANSACTION_ID);
        } else if (status == RefundStatus.DECLINED) {
            refund.decline("원거래를 찾을 수 없습니다.");
        }
        return refund;
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

    // 고객이 신청하고 가맹점이 실행해 원장 결과를 기다리는 결제
    private Payment refundPendingPayment() {
        Payment payment = refundRequestedPayment();
        payment.refundStartedHistory(REFUND_REASON, PaymentActor.MERCHANT);
        return payment;
    }

    private Payment refundRequestedPayment() {
        Payment payment = approvedPayment();
        payment.refundRequestedByCustomerHistory(REFUND_REASON);
        return payment;
    }

    // 고객 환불 신청

    @Test
    void customerRequestsRefundOfOwnApprovedPayment() {
        Payment payment = approvedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        PaymentResult result = paymentService.requestRefund(USER_ID, PAYMENT_ID, "사이즈가 맞지 않음");

        assertEquals(PaymentStatus.REFUND_REQUESTED, result.status());
        PaymentHistory history = savedHistories(1).get(0);
        assertEquals(PaymentStatus.APPROVED, history.getPreviousStatus());
        assertEquals(PaymentStatus.REFUND_REQUESTED, history.getCurrentStatus());
        assertEquals("사이즈가 맞지 않음", history.getReason());
        assertEquals(PaymentActor.USER, history.getCreatedBy());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void returnsCurrentStateWithoutSavingWhenAlreadyRequested() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        PaymentResult result = paymentService.requestRefund(USER_ID, PAYMENT_ID, "다시 신청");

        assertEquals(PaymentStatus.REFUND_REQUESTED, result.status());
        verify(paymentRepository, never()).save(any());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void throwsNotFoundWhenRequestingRefundOfOtherUsersPayment() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.requestRefund(OTHER_USER_ID, PAYMENT_ID, "사유"));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void throwsPeriodExpiredWhenRequestingRefundAfterRefundPeriod() {
        when(paymentHistoryRepository.findApprovedAt(PAYMENT_ID))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(3)).minus(Duration.ofMinutes(1))));
        Payment payment = approvedPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.requestRefund(USER_ID, PAYMENT_ID, "사유"));

        assertEquals(PaymentErrorCode.REFUND_PERIOD_EXPIRED, exception.getErrorCode());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void throwsRefundInProgressWhenRequestingRefundWhileRefundPending() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundPendingPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.requestRefund(USER_ID, PAYMENT_ID, "사유"));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, exception.getErrorCode());
    }

    @Test
    void returnsRequestedStateWhenConcurrentRequestWonFirst() {
        // 트랜잭션 안 조회, 충돌 후 재조회 순서로 결제를 읽습니다.
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(approvedPayment()), Optional.of(refundRequestedPayment()));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));

        PaymentResult result = paymentService.requestRefund(USER_ID, PAYMENT_ID, "사유");

        assertEquals(PaymentStatus.REFUND_REQUESTED, result.status());
    }

    @Test
    void throwsConflictWhenOtherChangeWonFirstDuringRequest() {
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(approvedPayment()), Optional.of(refundPendingPayment()));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.requestRefund(USER_ID, PAYMENT_ID, "사유"));

        assertEquals(CommonErrorCode.CONFLICT, exception.getErrorCode());
    }

    // 고객 환불 신청 취소

    @Test
    void customerCancelsRefundRequest() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        PaymentResult result = paymentService.cancelRefundRequest(USER_ID, PAYMENT_ID);

        assertEquals(PaymentStatus.APPROVED, result.status());
        PaymentHistory history = savedHistories(1).get(0);
        assertEquals(PaymentStatus.REFUND_REQUESTED, history.getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, history.getCurrentStatus());
        assertEquals(PaymentActor.USER, history.getCreatedBy());
    }

    @Test
    void throwsNotRequestedWhenCancellingWithoutRequest() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.cancelRefundRequest(USER_ID, PAYMENT_ID));

        assertEquals(PaymentErrorCode.REFUND_NOT_REQUESTED, exception.getErrorCode());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void throwsNotFoundWhenCancellingOtherUsersRequest() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.cancelRefundRequest(OTHER_USER_ID, PAYMENT_ID));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
    }

    // 결제 상세의 환불 신청 요약

    @Test
    void ownPaymentDetailIncludesRefundRequestSummary() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));
        when(paymentHistoryRepository.findAllByPaymentId(PAYMENT_ID)).thenReturn(List.of(
                PaymentHistory.of(PAYMENT_ID, PaymentStatus.APPROVED, PaymentStatus.REFUND_REQUESTED,
                        REFUND_REASON, PaymentActor.USER)));

        PaymentResult result = paymentService.getPayment(USER_ID, PAYMENT_ID);

        assertEquals(RefundRequestSummary.Result.REQUESTED, result.refundRequest().result());
        assertEquals(REFUND_REASON, result.refundRequest().requestReason());
    }

    @Test
    void merchantPaymentDetailIncludesRejectedRefundRequest() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        when(paymentHistoryRepository.findAllByPaymentId(PAYMENT_ID)).thenReturn(List.of(
                PaymentHistory.of(PAYMENT_ID, PaymentStatus.APPROVED, PaymentStatus.REFUND_REQUESTED,
                        REFUND_REASON, PaymentActor.USER),
                PaymentHistory.of(PAYMENT_ID, PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED,
                        "사용 흔적이 있음", PaymentActor.MERCHANT)));

        PaymentResult result = paymentService.getMerchantPayment(OWNER, MERCHANT_ID, PAYMENT_ID);

        assertEquals(RefundRequestSummary.Result.REJECTED, result.refundRequest().result());
        assertEquals("사용 흔적이 있음", result.refundRequest().rejectReason());
    }

    @Test
    void detailOfNeverApprovedPaymentSkipsHistoryLookup() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(paymentWithId(existingPayment(10_000L))));

        PaymentResult result = paymentService.getPayment(USER_ID, PAYMENT_ID);

        assertNull(result.refundRequest());
        verify(paymentHistoryRepository, never()).findAllByPaymentId(any());
    }

    // 결제 생성 응답에는 요약을 넣지 않아 이력 조회가 늘지 않습니다.
    @Test
    void paymentRequestDoesNotLookUpHistories() {
        givenWallet();
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));

        PaymentResult result = paymentService.paymentRequest(command(10_000L));

        assertNull(result.refundRequest());
        verify(paymentHistoryRepository, never()).findAllByPaymentId(any());
    }

    // 3일 넘게 처리되지 않은 환불 신청 목록

    @Test
    void listsOverdueRefundRequestsOldestFirstWithReasons() {
        Payment withReason = refundRequestedPayment();
        Payment other = existingPayment(5_000L);
        ReflectionTestUtils.setField(other, "id", OTHER_PAYMENT_ID);
        other.approvedHistory(LEDGER_TRANSACTION_ID);
        other.refundRequestedByCustomerHistory("색상 변경");
        when(paymentRepository.findRefundRequestsUpdatedBefore(any(), any()))
                .thenReturn(new PageImpl<>(List.of(withReason, other)));
        Map<UUID, String> reasons = new HashMap<>();
        reasons.put(PAYMENT_ID, REFUND_REASON);
        reasons.put(OTHER_PAYMENT_ID, "색상 변경");
        when(paymentHistoryRepository.findRefundRequestReasons(List.of(PAYMENT_ID, OTHER_PAYMENT_ID))).thenReturn(reasons);

        Instant before = Instant.now();
        Page<OverdueRefundRequestResult> result = paymentService.getOverdueRefundRequests(0, 20);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findRefundRequestsUpdatedBefore(cutoff.capture(), pageable.capture());
        // 기준 시각은 지금으로부터 3일 전
        assertTrue(!cutoff.getValue().isAfter(before.minus(Duration.ofDays(3)).plusSeconds(1)));
        assertTrue(cutoff.getValue().isAfter(before.minus(Duration.ofDays(3)).minusSeconds(5)));
        assertEquals(Sort.by(Sort.Order.asc("updatedAt"), Sort.Order.asc("id")), pageable.getValue().getSort());

        assertEquals(2, result.getContent().size());
        assertEquals(PAYMENT_ID, result.getContent().get(0).paymentId());
        assertEquals(REFUND_REASON, result.getContent().get(0).requestReason());
        assertEquals(USER_ID, result.getContent().get(0).userId());
        assertEquals(MERCHANT_ID, result.getContent().get(0).merchantId());
        assertEquals("색상 변경", result.getContent().get(1).requestReason());
    }

    @Test
    void throwsInvalidRequestForInvalidOverduePage() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getOverdueRefundRequests(-1, 20));

        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }

    // 가맹점 환불 신청 거절

    @Test
    void merchantOwnerRejectsRefundRequestAsMerchant() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        PaymentResult result = paymentService.rejectRefundRequest(OWNER, PAYMENT_ID, "사용 흔적이 있음");

        assertEquals(PaymentStatus.APPROVED, result.status());
        PaymentHistory history = savedHistories(1).get(0);
        assertEquals(PaymentStatus.REFUND_REQUESTED, history.getPreviousStatus());
        assertEquals("사용 흔적이 있음", history.getReason());
        assertEquals(PaymentActor.MERCHANT, history.getCreatedBy());
    }

    @Test
    void masterRejectsRefundRequestAsMaster() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        paymentService.rejectRefundRequest(new AuthenticatedUser(OTHER_USER_ID, AuthRole.MASTER), PAYMENT_ID,
                "고객센터 확인 결과 환불 대상 아님");

        assertEquals(PaymentActor.MASTER, savedHistories(1).get(0).getCreatedBy());
    }

    @Test
    void throwsNotFoundWhenOtherUserRejectsRefundRequest() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundRequestedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.rejectRefundRequest(new AuthenticatedUser(USER_ID, AuthRole.USER), PAYMENT_ID,
                        "사유"));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verify(paymentHistoryRepository, never()).save(any());
    }

    @Test
    void throwsNotRequestedWhenRejectingWithoutRequest() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.rejectRefundRequest(OWNER, PAYMENT_ID, "사유"));

        assertEquals(PaymentErrorCode.REFUND_NOT_REQUESTED, exception.getErrorCode());
    }

    private Refund existingRefund() {
        Refund refund = Refund.request(PAYMENT_ID, OWNER_ID, REFUND_KEY, 10_000L, CurrencyCode.KRW, REFUND_REASON);
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

    // ---------- 가맹점 결제 조회 ----------

    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, AuthRole.USER);

    @Test
    void returnsMerchantPaymentsWithCompletedRefundTotals() {
        Payment partlyRefunded = savedPayment(PAYMENT_ID, CREATED_AT);
        Payment notRefunded = savedPayment(OLDER_PAYMENT_ID, CREATED_AT.minusSeconds(60));
        when(paymentRepository.findPageByMerchantId(any(), any(), any()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(partlyRefunded, notRefunded), invocation.getArgument(2), 2));
        when(refundRepository.findAllByPaymentIdIn(List.of(PAYMENT_ID, OLDER_PAYMENT_ID))).thenReturn(List.of(
                refundOf(PAYMENT_ID, 3_000L, RefundStatus.COMPLETED),
                refundOf(PAYMENT_ID, 2_000L, RefundStatus.DECLINED)));

        Page<MerchantPaymentListResult> result = paymentService.getMerchantPayments(OWNER, MERCHANT_ID, null, 0, 20);

        assertEquals(2, result.getTotalElements());
        MerchantPaymentListResult first = result.getContent().get(0);
        assertEquals(PAYMENT_ID, first.paymentId());
        assertEquals(10_000L, first.amount());
        assertEquals(3_000L, first.refundedAmount());
        assertEquals("테스트 주문", first.paymentInfo());
        assertEquals(0L, result.getContent().get(1).refundedAmount());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findPageByMerchantId(eq(MERCHANT_ID), eq(null), captor.capture());
        assertEquals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")), captor.getValue().getSort());
    }

    @Test
    void passesStatusFilterForMerchantPayments() {
        when(paymentRepository.findPageByMerchantId(any(), any(), any())).thenReturn(Page.empty());

        paymentService.getMerchantPayments(OWNER, MERCHANT_ID, PaymentStatus.APPROVED, 0, 20);

        verify(paymentRepository).findPageByMerchantId(eq(MERCHANT_ID), eq(PaymentStatus.APPROVED), any());
    }

    @Test
    void masterCanListPaymentsOfAnyMerchant() {
        when(paymentRepository.findPageByMerchantId(any(), any(), any())).thenReturn(Page.empty());

        Page<MerchantPaymentListResult> result = paymentService.getMerchantPayments(
                new AuthenticatedUser(OTHER_USER_ID, AuthRole.MASTER), MERCHANT_ID, null, 0, 20);

        assertTrue(result.isEmpty());
    }

    @Test
    void throwsMerchantNotFoundWhenNonOwnerListsMerchantPayments() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getMerchantPayments(
                        new AuthenticatedUser(USER_ID, AuthRole.USER), MERCHANT_ID, null, 0, 20));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
        verify(paymentRepository, never()).findPageByMerchantId(any(), any(), any());
    }

    @Test
    void throwsMerchantNotFoundForMissingMerchant() {
        UUID missingMerchantId = UUID.fromString("abababab-abab-abab-abab-abababababab");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getMerchantPayments(
                        new AuthenticatedUser(OTHER_USER_ID, AuthRole.MASTER), missingMerchantId, null, 0, 20));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void returnsMerchantPaymentWithRefunds() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));
        savedRefunds.add(refundOf(PAYMENT_ID, 3_000L, RefundStatus.COMPLETED));

        PaymentResult result = paymentService.getMerchantPayment(OWNER, MERCHANT_ID, PAYMENT_ID);

        assertEquals(PAYMENT_ID, result.paymentId());
        assertEquals(1, result.refunds().size());
        assertEquals(3_000L, result.refunds().get(0).amount());
    }

    @Test
    void throwsPaymentNotFoundForOtherMerchantsPayment() {
        Payment otherMerchantPayment = approvedPayment();
        ReflectionTestUtils.setField(otherMerchantPayment, "merchantId", UUID.randomUUID());
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(otherMerchantPayment));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getMerchantPayment(OWNER, MERCHANT_ID, PAYMENT_ID));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void throwsMerchantNotFoundWhenNonOwnerReadsMerchantPayment() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getMerchantPayment(
                        new AuthenticatedUser(USER_ID, AuthRole.USER), MERCHANT_ID, PAYMENT_ID));

        assertEquals(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
        verify(paymentRepository, never()).findById(any());
    }

    private Refund refundOf(UUID paymentId, long amount, RefundStatus status) {
        Refund refund = Refund.request(paymentId, OWNER_ID, UUID.randomUUID().toString(), amount,
                CurrencyCode.KRW, REFUND_REASON);
        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
        if (status == RefundStatus.COMPLETED) {
            refund.complete(REFUND_TRANSACTION_ID);
        } else if (status == RefundStatus.DECLINED) {
            refund.decline("원거래를 찾을 수 없습니다.");
        }
        return refund;
    }

    // ---------- 복구 배치 ----------

    private static final Instant NOW = Instant.parse("2026-10-30T01:00:00Z");

    private void givenClaimable() {
        when(paymentRepository.claimForRecovery(any(), any(), any())).thenReturn(1);
        when(refundRepository.claimForRecovery(any(), any(), any())).thenReturn(1);
    }

    private Payment pendingWithAttempts(UUID id, int attemptCount) {
        Payment payment = savedPayment(id, CREATED_AT);
        ReflectionTestUtils.setField(payment, "attemptCount", attemptCount);
        return payment;
    }

    private Refund requestedWithAttempts(int attemptCount) {
        Refund refund = existingRefund();
        ReflectionTestUtils.setField(refund, "attemptCount", attemptCount);
        return refund;
    }

    @Test
    void settlesRecoveryTargetPayments() {
        givenClaimable();
        Payment first = savedPayment(PAYMENT_ID, CREATED_AT);
        Payment second = savedPayment(OTHER_PAYMENT_ID, CREATED_AT);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(first, second));
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));

        paymentService.recoverPayments(NOW, 100);

        assertEquals(PaymentStatus.APPROVED, first.getStatus());
        assertEquals(PaymentStatus.APPROVED, second.getStatus());
        verify(ledgerClient, times(2)).createTransaction(any());
    }

    @Test
    void skipsPaymentWhenClaimFails() {
        Payment payment = savedPayment(PAYMENT_ID, CREATED_AT);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(payment));
        when(paymentRepository.claimForRecovery(any(), any(), any())).thenReturn(0);

        paymentService.recoverPayments(NOW, 100);

        // 다른 인스턴스가 먼저 선점했거나 그 사이 결과가 반영된 건은 원장에 다시 요청하지 않음
        verifyNoInteractions(ledgerClient);
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    void continuesPaymentRecoveryWhenOnePaymentFails() {
        givenClaimable();
        Payment first = savedPayment(PAYMENT_ID, CREATED_AT);
        Payment second = savedPayment(OTHER_PAYMENT_ID, CREATED_AT);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(first, second));
        when(ledgerClient.createTransaction(any()))
                .thenThrow(new IllegalStateException("원장 연결 실패"))
                .thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));

        paymentService.recoverPayments(NOW, 100);

        assertEquals(PaymentStatus.PENDING, first.getStatus());
        assertEquals(PaymentStatus.APPROVED, second.getStatus());
    }

    @Test
    void stopsPaymentRecoveryWhenLedgerKeepsReturningUnknown() {
        givenClaimable();
        List<Payment> pendings = List.of(
                savedPayment(UUID.randomUUID(), CREATED_AT), savedPayment(UUID.randomUUID(), CREATED_AT),
                savedPayment(UUID.randomUUID(), CREATED_AT), savedPayment(UUID.randomUUID(), CREATED_AT));
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(pendings);
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        paymentService.recoverPayments(NOW, 100);

        verify(ledgerClient, times(3)).createTransaction(any());
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(paymentRepository, times(3)).recordUnknownAttempt(any(), anyInt(), any(), any(), any());
    }

    @Test
    void resetsConsecutiveUnknownWhenLedgerDoesNotAccept() {
        givenClaimable();
        UUID notAcceptedId = UUID.randomUUID();
        List<Payment> pendings = List.of(
                savedPayment(UUID.randomUUID(), CREATED_AT), savedPayment(UUID.randomUUID(), CREATED_AT),
                savedPayment(notAcceptedId, CREATED_AT),
                savedPayment(UUID.randomUUID(), CREATED_AT), savedPayment(UUID.randomUUID(), CREATED_AT));
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(pendings);
        when(ledgerClient.createTransaction(any()))
                .thenReturn(PaymentLedgerResult.unknown(), PaymentLedgerResult.unknown(), PaymentLedgerResult.notAccepted(),
                        PaymentLedgerResult.unknown(), PaymentLedgerResult.unknown());
        Payment manualReview = savedPayment(notAcceptedId, CREATED_AT);
        ReflectionTestUtils.setField(manualReview, "status", PaymentStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(manualReview, "reviewReason", SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        when(paymentRepository.markManualReview(eq(notAcceptedId), any(), any())).thenReturn(1);
        when(paymentRepository.findById(notAcceptedId)).thenReturn(Optional.of(manualReview));

        paymentService.recoverPayments(NOW, 100);

        // 원장이 받아들이지 않은 응답은 원장이 응답한 것이므로 연속 결과 모름을 끊는다
        verify(ledgerClient, times(5)).createTransaction(any());
    }

    @Test
    void recordsUnknownAttemptAndKeepsPendingBeforeLimit() {
        givenClaimable();
        Payment payment = pendingWithAttempts(PAYMENT_ID, 3);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(payment));
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.unknown());
        when(paymentRepository.recordUnknownAttempt(any(), anyInt(), any(), any(), any())).thenReturn(1);

        paymentService.recoverPayments(NOW, 100);

        verify(paymentRepository).recordUnknownAttempt(eq(PAYMENT_ID), eq(3), eq(PaymentStatus.PENDING), eq(null), any());
        verifyNoInteractions(paymentHistoryRepository);
    }

    @Test
    void marksManualReviewWithHistoryOnFifthUnknown() {
        givenClaimable();
        Payment payment = pendingWithAttempts(PAYMENT_ID, 4);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(payment));
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.unknown());
        when(paymentRepository.recordUnknownAttempt(any(), anyInt(), any(), any(), any())).thenReturn(1);

        paymentService.recoverPayments(NOW, 100);

        verify(paymentRepository).recordUnknownAttempt(eq(PAYMENT_ID), eq(4), eq(PaymentStatus.MANUAL_REVIEW),
                eq(SettlementReviewReason.RETRY_EXHAUSTED), any());
        List<PaymentHistory> histories = savedHistories(1);
        assertEquals(PaymentStatus.PENDING, histories.get(0).getPreviousStatus());
        assertEquals(PaymentStatus.MANUAL_REVIEW, histories.get(0).getCurrentStatus());
        assertEquals("RETRY_EXHAUSTED", histories.get(0).getReason());
        assertEquals(PaymentActor.SYSTEM, histories.get(0).getCreatedBy());
    }

    @Test
    void doesNotSaveHistoryWhenFifthUnknownUpdateMissed() {
        givenClaimable();
        Payment payment = pendingWithAttempts(PAYMENT_ID, 4);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(payment));
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.unknown());
        when(paymentRepository.recordUnknownAttempt(any(), anyInt(), any(), any(), any())).thenReturn(0);

        paymentService.recoverPayments(NOW, 100);

        // 다른 곳에서 먼저 바꿨으면(상태·시도 횟수가 다르면) 이력을 남기지 않음
        verifyNoInteractions(paymentHistoryRepository);
    }

    @Test
    void continuesPaymentRecoveryWhenRecordingUnknownFails() {
        givenClaimable();
        Payment first = savedPayment(PAYMENT_ID, CREATED_AT);
        Payment second = savedPayment(OTHER_PAYMENT_ID, CREATED_AT);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(first, second));
        when(ledgerClient.createTransaction(any()))
                .thenReturn(PaymentLedgerResult.unknown())
                .thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));
        when(paymentRepository.recordUnknownAttempt(eq(PAYMENT_ID), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalStateException("DB 연결 실패"));

        paymentService.recoverPayments(NOW, 100);

        assertEquals(PaymentStatus.PENDING, first.getStatus());
        assertEquals(PaymentStatus.APPROVED, second.getStatus());
    }

    @Test
    void readsSettledPaymentWhenAnotherRequestSettledItFirst() {
        givenClaimable();
        Payment payment = savedPayment(PAYMENT_ID, CREATED_AT);
        when(paymentRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(payment));
        when(ledgerClient.createTransaction(any())).thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(approvedPayment()));

        paymentService.recoverPayments(NOW, 100);

        verify(paymentRepository).findById(PAYMENT_ID);
        verify(paymentRepository, never()).recordUnknownAttempt(any(), anyInt(), any(), any(), any());
    }

    @Test
    void settlesRecoveryTargetRefunds() {
        givenClaimable();
        Refund refund = existingRefund();
        Payment payment = refundPendingPayment();
        when(refundRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(refund));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));

        paymentService.recoverRefunds(NOW, 100);

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void returnsPaymentToApprovedWithoutThrowingWhenRecoveredRefundIsDeclined() {
        givenClaimable();
        Refund refund = existingRefund();
        Payment payment = refundPendingPayment();
        when(refundRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(refund));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.declined("원거래를 찾을 수 없습니다."));

        paymentService.recoverRefunds(NOW, 100);

        assertEquals(RefundStatus.DECLINED, refund.getStatus());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
    }

    @Test
    void skipsRefundWhenClaimFails() {
        Refund refund = existingRefund();
        when(refundRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(refund));
        when(refundRepository.claimForRecovery(any(), any(), any())).thenReturn(0);

        paymentService.recoverRefunds(NOW, 100);

        verifyNoInteractions(ledgerClient);
        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
    }

    @Test
    void stopsRefundRecoveryWhenLedgerKeepsReturningUnknown() {
        givenClaimable();
        List<Refund> requested = List.of(existingRefund(), existingRefund(), existingRefund(), existingRefund());
        when(refundRepository.findRecoveryTargets(NOW, 100)).thenReturn(requested);
        when(paymentRepository.findById(PAYMENT_ID)).thenAnswer(invocation -> Optional.of(refundPendingPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        paymentService.recoverRefunds(NOW, 100);

        verify(ledgerClient, times(3)).refundTransaction(any());
        verify(refundRepository, never()).save(any());
        verify(refundRepository, times(3)).recordUnknownAttempt(any(), anyInt(), any(), any(), any());
    }

    @Test
    void marksRefundManualReviewWithoutHistoryOnFifthUnknown() {
        givenClaimable();
        Refund refund = requestedWithAttempts(4);
        when(refundRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(refund));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(refundPendingPayment()));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.unknown());
        when(refundRepository.recordUnknownAttempt(any(), anyInt(), any(), any(), any())).thenReturn(1);

        paymentService.recoverRefunds(NOW, 100);

        verify(refundRepository).recordUnknownAttempt(eq(REFUND_ID), eq(4), eq(RefundStatus.MANUAL_REVIEW),
                eq(SettlementReviewReason.RETRY_EXHAUSTED), any());
        // 결제는 REFUND_PENDING 그대로라 결제 이력을 남기지 않음
        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(paymentHistoryRepository);
    }

    @Test
    void continuesRefundRecoveryWhenPaymentIsMissing() {
        givenClaimable();
        Refund refund = existingRefund();
        when(refundRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(refund));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        paymentService.recoverRefunds(NOW, 100);

        verifyNoInteractions(ledgerClient);
        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
    }

    @Test
    void readsSettledRefundWhenAnotherRequestSettledItFirst() {
        givenClaimable();
        Refund refund = existingRefund();
        Refund completedByOther = existingRefund();
        completedByOther.complete(REFUND_TRANSACTION_ID);
        Payment refundedByOther = refundPendingPayment();
        refundedByOther.refundCompletedHistory(10_000L, 0L);
        when(refundRepository.findRecoveryTargets(NOW, 100)).thenReturn(List.of(refund));
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(refundPendingPayment()), Optional.of(refundedByOther));
        when(refundRepository.findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY))
                .thenReturn(Optional.of(completedByOther));
        when(ledgerClient.refundTransaction(any())).thenReturn(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID));
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));

        paymentService.recoverRefunds(NOW, 100);

        verify(refundRepository).findByUserIdAndIdempotencyKey(OWNER_ID, REFUND_KEY);
        verify(refundRepository, never()).recordUnknownAttempt(any(), anyInt(), any(), any(), any());
    }

    @Test
    void countsManualReviewPaymentsAndRefunds() {
        when(paymentRepository.countByStatus(PaymentStatus.MANUAL_REVIEW)).thenReturn(2L);
        when(refundRepository.countByStatus(RefundStatus.MANUAL_REVIEW)).thenReturn(1L);

        assertEquals(2L, paymentService.countManualReviewPayments());
        assertEquals(1L, paymentService.countManualReviewRefunds());
    }

    // ---------- 오래 막힌 건 집계 ----------

    @Test
    void findsStuckSettlementsWithSameCutoffAndSampleSize() {
        Instant cutoff = Instant.parse("2026-10-30T00:30:00Z");
        UUID stuckRefundId = UUID.randomUUID();
        when(paymentRepository.countPendingCreatedBefore(cutoff)).thenReturn(12L);
        when(paymentRepository.findPendingIdsCreatedBefore(cutoff, 10)).thenReturn(List.of(PAYMENT_ID));
        when(refundRepository.countRequestedCreatedBefore(cutoff)).thenReturn(1L);
        when(refundRepository.findRequestedIdsCreatedBefore(cutoff, 10)).thenReturn(List.of(stuckRefundId));

        StuckSettlementResult result = paymentService.findStuckSettlements(cutoff, 10);

        assertEquals(12L, result.paymentCount());
        assertEquals(List.of(PAYMENT_ID), result.paymentIds());
        assertEquals(1L, result.refundCount());
        assertEquals(List.of(stuckRefundId), result.refundIds());
    }
}

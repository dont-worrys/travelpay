package com.travelpay.payment.application.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.application.payment.dto.ManualReviewPreview;
import com.travelpay.payment.application.payment.dto.ManualReviewResolveResult;
import com.travelpay.payment.application.payment.dto.PaymentReviewDetailResult;
import com.travelpay.payment.application.payment.dto.RefundReviewDetailResult;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentReviewResolution;
import com.travelpay.payment.domain.payment.PaymentReviewResolutionRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundRepository;
import com.travelpay.payment.domain.payment.RefundStatus;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.ReviewTarget;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentReviewServiceTest {

    private static final UUID MASTER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    // 가맹점 ID와 다르게 두어 기대 항목이 가맹점 원장 지갑으로 만들어지는지 확인
    private static final UUID MERCHANT_LEDGER_WALLET_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID REFUND_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID PAYMENT_TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID REFUND_TRANSACTION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final long PAYMENT_AMOUNT = 10_000L;
    private static final String MEMO = "원장 복구 확인 후 처리";

    private PaymentRepository paymentRepository;
    private PaymentHistoryRepository paymentHistoryRepository;
    private RefundRepository refundRepository;
    private PaymentReviewResolutionRepository resolutionRepository;
    private PaymentLedgerClient ledgerClient;
    private MerchantRepository merchantRepository;
    private PaymentReviewService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentHistoryRepository = mock(PaymentHistoryRepository.class);
        refundRepository = mock(RefundRepository.class);
        resolutionRepository = mock(PaymentReviewResolutionRepository.class);
        ledgerClient = mock(PaymentLedgerClient.class);
        merchantRepository = mock(MerchantRepository.class);
        service = new PaymentReviewService(paymentRepository, merchantRepository, paymentHistoryRepository,
                refundRepository, resolutionRepository, ledgerClient, TransactionOperations.withoutTransaction());

        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Merchant merchant = mock(Merchant.class);
        when(merchant.getLedgerWalletId()).thenReturn(MERCHANT_LEDGER_WALLET_ID);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
    }

    // ── 결제 처리 ──

    @Test
    void p1_resolvesPostedPaymentAsApproved() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentPosted());

        ManualReviewResolveResult result = service.resolvePayment(MASTER_ID, PAYMENT_ID, MEMO);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(PAYMENT_TRANSACTION_ID, payment.getLedgerId());
        assertNull(payment.getReviewReason());
        assertEquals(new ManualReviewResolveResult(PAYMENT_ID, null, "APPROVED", PaymentStatus.APPROVED,
                ReviewLedgerStatus.POSTED, PAYMENT_TRANSACTION_ID), result);

        PaymentHistory history = savedHistory();
        assertEquals(PaymentStatus.MANUAL_REVIEW, history.getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, history.getCurrentStatus());
        assertEquals("원장에 거래가 반영되어 있음", history.getReason());
        assertEquals(PaymentActor.MASTER, history.getCreatedBy());

        PaymentReviewResolution resolution = savedResolution();
        assertEquals(PAYMENT_ID, resolution.getPaymentId());
        assertNull(resolution.getRefundId());
        assertEquals(SettlementReviewReason.RETRY_EXHAUSTED, resolution.getReviewReason());
        assertEquals(ReviewLedgerStatus.POSTED, resolution.getLedgerStatus());
        assertEquals(PAYMENT_TRANSACTION_ID, resolution.getLedgerTransactionId());
        assertEquals("APPROVED", resolution.getToStatus());
        assertEquals(MASTER_ID, resolution.getActorId());
        assertEquals("재시도 한도 초과, 원장 반영 확인 → 결제 승인", resolution.getDescription());
        assertEquals(MEMO, resolution.getMemo());
    }

    @Test
    void p2_resolvesRejectedPaymentAsDeclinedWithLedgerReason() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID))
                .thenReturn(PaymentLedgerLookup.rejected(PAYMENT_TRANSACTION_ID, "잔액이 부족합니다."));

        service.resolvePayment(MASTER_ID, PAYMENT_ID, null);

        assertEquals(PaymentStatus.DECLINED, payment.getStatus());
        assertEquals("잔액이 부족합니다.", payment.getDeclineReason());
        assertNull(payment.getReviewReason());
        assertEquals("잔액이 부족합니다.", savedHistory().getReason());
        PaymentReviewResolution resolution = savedResolution();
        assertEquals(ReviewLedgerStatus.REJECTED, resolution.getLedgerStatus());
        assertEquals(PAYMENT_TRANSACTION_ID, resolution.getLedgerTransactionId());
        assertEquals("재시도 한도 초과, 원장 거절 확인 → 결제 거절", resolution.getDescription());
        assertNull(resolution.getMemo());
    }

    @Test
    void p3_declinesPaymentMissingFromLedgerAsTimedOut() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(PaymentLedgerLookup.notFound());

        ManualReviewResolveResult result = service.resolvePayment(MASTER_ID, PAYMENT_ID, MEMO);

        assertEquals(PaymentStatus.DECLINED, payment.getStatus());
        assertEquals("결제 처리 시간이 초과되어 취소되었습니다.", payment.getDeclineReason());
        assertEquals(ReviewLedgerStatus.NOT_FOUND, result.ledgerStatus());
        assertNull(result.ledgerTransactionId());
        PaymentReviewResolution resolution = savedResolution();
        assertNull(resolution.getLedgerTransactionId());
        assertEquals("재시도 한도 초과, 원장 기록 없음 → 결제 시간 초과 취소", resolution.getDescription());
    }

    @ParameterizedTest
    @EnumSource(value = ReviewLedgerStatus.class, names = {"REJECTED", "NOT_FOUND"})
    void p4_declinesNotAcceptedPaymentWhenLedgerMovedNoMoney(ReviewLedgerStatus ledgerStatus) {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentLookup(ledgerStatus));

        service.resolvePayment(MASTER_ID, PAYMENT_ID, null);

        assertEquals(PaymentStatus.DECLINED, payment.getStatus());
        assertEquals("결제 처리 중 오류로 취소되었습니다.", payment.getDeclineReason());
        PaymentReviewResolution resolution = savedResolution();
        assertEquals(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, resolution.getReviewReason());
        assertEquals("원장 요청 거부, " + ledgerText(ledgerStatus) + " → 결제 취소", resolution.getDescription());
    }

    // 원장이 요청을 받지 않았어도 원장 거래가 결제와 같으면 돈은 의도대로 움직인 것 → 승인
    @Test
    void p5_approvesNotAcceptedPaymentWhenLedgerMatches() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentPosted());

        ManualReviewResolveResult result = service.resolvePayment(MASTER_ID, PAYMENT_ID, MEMO);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(PAYMENT_TRANSACTION_ID, payment.getLedgerId());
        assertEquals("APPROVED", result.toStatus());
        PaymentReviewResolution resolution = savedResolution();
        assertEquals(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, resolution.getReviewReason());
        assertEquals("원장 요청 거부, 원장 반영 확인 → 결제 승인", resolution.getDescription());
    }

    @Test
    void p5_keepsNotAcceptedPaymentWhenLedgerDiffers() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentPosted(500L));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolvePayment(MASTER_ID, PAYMENT_ID, MEMO));

        assertEquals(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED, exception.getErrorCode());
        assertEquals(PaymentStatus.MANUAL_REVIEW, payment.getStatus());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    @Test
    void rejectsUnknownPayment() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolvePayment(MASTER_ID, PAYMENT_ID, null));

        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void rejectsPaymentNotInManualReviewWithoutCallingLedger() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.APPROVED);
        ReflectionTestUtils.setField(payment, "reviewReason", null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolvePayment(MASTER_ID, PAYMENT_ID, null));

        assertEquals(PaymentErrorCode.NOT_MANUAL_REVIEW, exception.getErrorCode());
        verifyNoInteractions(ledgerClient, paymentHistoryRepository, resolutionRepository);
    }

    @Test
    void keepsPaymentWhenLedgerLookupFails() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID))
                .thenThrow(new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolvePayment(MASTER_ID, PAYMENT_ID, null));

        assertEquals(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE, exception.getErrorCode());
        assertEquals(PaymentStatus.MANUAL_REVIEW, payment.getStatus());
        assertEquals(SettlementReviewReason.RETRY_EXHAUSTED, payment.getReviewReason());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    @Test
    void reportsNotManualReviewWhenAnotherResolutionWonTheRace() {
        givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentPosted());
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, PAYMENT_ID));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolvePayment(MASTER_ID, PAYMENT_ID, null));

        assertEquals(PaymentErrorCode.NOT_MANUAL_REVIEW, exception.getErrorCode());
        verifyNoInteractions(resolutionRepository);
    }

    // ── 환불 처리 ──

    @Test
    void r1_completesPostedFullRefundAndMarksPaymentRefunded() {
        Payment payment = givenRefundPendingPayment();
        Refund refund = givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundPosted(PAYMENT_AMOUNT));
        givenResolveCompletedAppliesTo(refund);

        ManualReviewResolveResult result = service.resolveRefund(MASTER_ID, REFUND_ID, MEMO);

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(new ManualReviewResolveResult(PAYMENT_ID, REFUND_ID, "COMPLETED", PaymentStatus.REFUNDED,
                ReviewLedgerStatus.POSTED, REFUND_TRANSACTION_ID), result);

        PaymentHistory history = savedHistory();
        assertEquals(PaymentStatus.REFUND_PENDING, history.getPreviousStatus());
        assertEquals(PaymentStatus.REFUNDED, history.getCurrentStatus());
        assertEquals(PaymentActor.MASTER, history.getCreatedBy());

        PaymentReviewResolution resolution = savedResolution();
        assertEquals(PAYMENT_ID, resolution.getPaymentId());
        assertEquals(REFUND_ID, resolution.getRefundId());
        assertEquals("COMPLETED", resolution.getToStatus());
        assertEquals(REFUND_TRANSACTION_ID, resolution.getLedgerTransactionId());
        assertEquals(MASTER_ID, resolution.getActorId());
        assertEquals("재시도 한도 초과, 원장 반영 확인 → 환불 완료", resolution.getDescription());
        assertEquals(MEMO, resolution.getMemo());
    }

    @Test
    void r1_completesPostedPartialRefundAndReturnsPaymentToApproved() {
        Payment payment = givenRefundPendingPayment();
        Refund refund = givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, 3_000L);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundPosted(3_000L));
        givenResolveCompletedAppliesTo(refund);

        ManualReviewResolveResult result = service.resolveRefund(MASTER_ID, REFUND_ID, null);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(PaymentStatus.APPROVED, result.paymentStatus());
        assertEquals("부분 환불 완료: 3000", savedHistory().getReason());
    }

    @Test
    void r2_declinesRejectedRefundWithLedgerReasonAndReturnsPaymentToApproved() {
        Payment payment = givenRefundPendingPayment();
        givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID))
                .thenReturn(PaymentLedgerLookup.rejected(REFUND_TRANSACTION_ID, "환불할 수 없는 계정입니다."));
        when(refundRepository.resolveDeclined(eq(REFUND_ID), eq("환불할 수 없는 계정입니다."), any())).thenReturn(1);

        ManualReviewResolveResult result = service.resolveRefund(MASTER_ID, REFUND_ID, null);

        assertEquals("DECLINED", result.toStatus());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        PaymentHistory history = savedHistory();
        assertEquals("환불할 수 없는 계정입니다.", history.getReason());
        assertEquals(PaymentActor.MASTER, history.getCreatedBy());
        assertEquals("재시도 한도 초과, 원장 거절 확인 → 환불 거절·취소", savedResolution().getDescription());
    }

    @Test
    void r3_returnsRefundMissingFromLedgerToRetryWithoutTouchingPayment() {
        Payment payment = givenRefundPendingPayment();
        givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(PaymentLedgerLookup.notFound());
        when(refundRepository.resolveRetry(eq(REFUND_ID), any())).thenReturn(1);

        ManualReviewResolveResult result = service.resolveRefund(MASTER_ID, REFUND_ID, null);

        assertEquals(new ManualReviewResolveResult(PAYMENT_ID, REFUND_ID, "REQUESTED", PaymentStatus.REFUND_PENDING,
                ReviewLedgerStatus.NOT_FOUND, null), result);
        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentHistoryRepository);
        PaymentReviewResolution resolution = savedResolution();
        assertEquals("REQUESTED", resolution.getToStatus());
        assertEquals("재시도 한도 초과, 원장 기록 없음 → 환불 재시도", resolution.getDescription());
    }

    @ParameterizedTest
    @EnumSource(value = ReviewLedgerStatus.class, names = {"REJECTED", "NOT_FOUND"})
    void r4_declinesNotAcceptedRefundWhenLedgerMovedNoMoney(ReviewLedgerStatus ledgerStatus) {
        Payment payment = givenRefundPendingPayment();
        givenManualReviewRefund(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundLookup(ledgerStatus));
        when(refundRepository.resolveDeclined(eq(REFUND_ID), eq("환불 처리 중 오류로 취소되었습니다."), any()))
                .thenReturn(1);

        ManualReviewResolveResult result = service.resolveRefund(MASTER_ID, REFUND_ID, null);

        assertEquals("DECLINED", result.toStatus());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals("환불 처리 중 오류로 취소되었습니다.", savedHistory().getReason());
        assertEquals("원장 요청 거부, " + ledgerText(ledgerStatus) + " → 환불 거절·취소",
                savedResolution().getDescription());
        verify(refundRepository, never()).resolveRetry(any(), any());
    }

    @Test
    void r5_completesNotAcceptedRefundWhenLedgerMatches() {
        Payment payment = givenRefundPendingPayment();
        Refund refund = givenManualReviewRefund(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundPosted(PAYMENT_AMOUNT));
        givenResolveCompletedAppliesTo(refund);

        ManualReviewResolveResult result = service.resolveRefund(MASTER_ID, REFUND_ID, null);

        assertEquals("COMPLETED", result.toStatus());
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals("원장 요청 거부, 원장 반영 확인 → 환불 완료", savedResolution().getDescription());
    }

    @Test
    void r5_keepsNotAcceptedRefundWhenLedgerDiffers() {
        givenRefundPendingPayment();
        givenManualReviewRefund(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundPosted(500L));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveRefund(MASTER_ID, REFUND_ID, null));

        assertEquals(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED, exception.getErrorCode());
        verify(refundRepository, never()).resolveCompleted(any(), any(), any());
        verify(refundRepository, never()).resolveDeclined(any(), any(), any());
        verify(refundRepository, never()).resolveRetry(any(), any());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    // ── 원장 내용 불일치 (원장 POSTED지만 금액·지갑이 우리 기록과 다름) ──

    // 로컬에서 재현한 상황: 결제는 큰 금액인데 원장에는 1만 원만 반영됨 → 승인하면 안 됨
    @Test
    void doesNotApprovePaymentWhenLedgerAmountDiffers() {
        Payment payment = givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentPosted(1_000L));

        PaymentReviewDetailResult detail = service.getPaymentReview(MASTER_ID, PAYMENT_ID);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolvePayment(MASTER_ID, PAYMENT_ID, MEMO));

        assertEquals(new ManualReviewPreview.Expected(false, null, null,
                "재시도 한도 초과, 원장 반영 확인(금액·지갑 불일치) → 원장 보정 필요(처리 불가)"),
                detail.preview().expected());
        assertEquals(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED, exception.getErrorCode());
        assertEquals(PaymentStatus.MANUAL_REVIEW, payment.getStatus());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    // 가맹점 ID가 아니라 가맹점 원장 지갑으로 비교해야 함
    @Test
    void doesNotApprovePaymentWhenLedgerWalletDiffers() {
        givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(PaymentLedgerLookup.posted(
                PAYMENT_TRANSACTION_ID, List.of(
                        new PaymentLedgerLookup.Entry(WALLET_ID, "KRW", "DEBIT", PAYMENT_AMOUNT, "PAYMENT_OUT"),
                        new PaymentLedgerLookup.Entry(MERCHANT_ID, "KRW", "CREDIT", PAYMENT_AMOUNT, "PAYMENT_IN"))));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolvePayment(MASTER_ID, PAYMENT_ID, null));

        assertEquals(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    @Test
    void doesNotCompleteRefundWhenLedgerAmountDiffers() {
        Payment payment = givenRefundPendingPayment();
        givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundPosted(1_000L));

        RefundReviewDetailResult detail = service.getRefundReview(MASTER_ID, REFUND_ID);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveRefund(MASTER_ID, REFUND_ID, null));

        assertEquals(false, detail.preview().expected().resolvable());
        assertEquals(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED, exception.getErrorCode());
        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        verify(refundRepository, never()).resolveCompleted(any(), any(), any());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    @Test
    void rejectsUnknownRefund() {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveRefund(MASTER_ID, REFUND_ID, null));

        assertEquals(PaymentErrorCode.REFUND_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void rejectsRefundNotInManualReviewWithoutCallingLedger() {
        Refund refund = givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, PAYMENT_AMOUNT);
        ReflectionTestUtils.setField(refund, "status", RefundStatus.REQUESTED);
        ReflectionTestUtils.setField(refund, "reviewReason", null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveRefund(MASTER_ID, REFUND_ID, null));

        assertEquals(PaymentErrorCode.NOT_MANUAL_REVIEW, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void reportsNotManualReviewWhenRefundWasResolvedConcurrently() {
        Payment payment = givenRefundPendingPayment();
        givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundPosted(PAYMENT_AMOUNT));
        when(refundRepository.resolveCompleted(eq(REFUND_ID), eq(REFUND_TRANSACTION_ID), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveRefund(MASTER_ID, REFUND_ID, null));

        assertEquals(PaymentErrorCode.NOT_MANUAL_REVIEW, exception.getErrorCode());
        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    // ── 단건 조회(예상 결과) ──

    // 단건 조회에서 보여 준 예상 결과가 실제 처리 결과와 같아야 함
    @ParameterizedTest
    @CsvSource({
            "RETRY_EXHAUSTED, POSTED",                // P1
            "RETRY_EXHAUSTED, REJECTED",              // P2
            "RETRY_EXHAUSTED, NOT_FOUND",             // P3
            "LEDGER_REQUEST_NOT_ACCEPTED, REJECTED",  // P4
            "LEDGER_REQUEST_NOT_ACCEPTED, NOT_FOUND", // P4
            "LEDGER_REQUEST_NOT_ACCEPTED, POSTED"     // P5 내용 같음
    })
    void paymentPreviewMatchesActualResolution(SettlementReviewReason reviewReason, ReviewLedgerStatus ledgerStatus) {
        givenManualReviewPayment(reviewReason);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentLookup(ledgerStatus));

        PaymentReviewDetailResult detail = service.getPaymentReview(MASTER_ID, PAYMENT_ID);
        ManualReviewResolveResult result = service.resolvePayment(MASTER_ID, PAYMENT_ID, null);

        ManualReviewPreview.Expected expected = detail.preview().expected();
        assertTrue(expected.resolvable());
        assertEquals(result.toStatus(), expected.status());
        assertEquals(result.paymentStatus(), expected.paymentStatus());
        assertEquals(savedResolution().getDescription(), expected.description());
        assertEquals(ledgerStatus, detail.preview().ledger().status());
    }

    @Test
    void paymentPreviewShowsCorrectionRequiredWithLedgerTransaction() {
        givenManualReviewPayment(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID)).thenReturn(paymentPosted(500L));

        PaymentReviewDetailResult detail = service.getPaymentReview(MASTER_ID, PAYMENT_ID);

        assertEquals(new ManualReviewPreview.Expected(false, null, null,
                "원장 요청 거부, 원장 반영 확인(금액·지갑 불일치) → 원장 보정 필요(처리 불가)"),
                detail.preview().expected());
        assertEquals(PAYMENT_TRANSACTION_ID, detail.preview().ledger().transactionId());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentHistoryRepository, resolutionRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "RETRY_EXHAUSTED, POSTED",                // R1
            "RETRY_EXHAUSTED, REJECTED",              // R2
            "RETRY_EXHAUSTED, NOT_FOUND",             // R3
            "LEDGER_REQUEST_NOT_ACCEPTED, REJECTED",  // R4
            "LEDGER_REQUEST_NOT_ACCEPTED, NOT_FOUND", // R4
            "LEDGER_REQUEST_NOT_ACCEPTED, POSTED"     // R5 내용 같음
    })
    void refundPreviewMatchesActualResolution(SettlementReviewReason reviewReason, ReviewLedgerStatus ledgerStatus) {
        givenRefundPendingPayment();
        Refund refund = givenManualReviewRefund(reviewReason, PAYMENT_AMOUNT);
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundLookup(ledgerStatus));
        givenResolveCompletedAppliesTo(refund);
        when(refundRepository.resolveDeclined(eq(REFUND_ID), any(), any())).thenReturn(1);
        when(refundRepository.resolveRetry(eq(REFUND_ID), any())).thenReturn(1);

        RefundReviewDetailResult detail = service.getRefundReview(MASTER_ID, REFUND_ID);
        ManualReviewResolveResult result = service.resolveRefund(MASTER_ID, REFUND_ID, null);

        ManualReviewPreview.Expected expected = detail.preview().expected();
        assertTrue(expected.resolvable());
        assertEquals(result.toStatus(), expected.status());
        assertEquals(result.paymentStatus(), expected.paymentStatus());
        assertEquals(savedResolution().getDescription(), expected.description());
    }

    @Test
    void partialRefundPreviewExpectsPaymentBackToApproved() {
        givenRefundPendingPayment();
        Refund refund = givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, 3_000L);
        Refund earlier = Refund.request(PAYMENT_ID, OWNER_ID, "refund-key-0", 2_000L, CurrencyCode.KRW, "부분 환불");
        ReflectionTestUtils.setField(earlier, "status", RefundStatus.COMPLETED);
        when(refundRepository.findAllByPaymentId(PAYMENT_ID)).thenReturn(List.of(earlier, refund));
        when(ledgerClient.findRefundTransaction(REFUND_ID)).thenReturn(refundPosted(3_000L));

        RefundReviewDetailResult detail = service.getRefundReview(MASTER_ID, REFUND_ID);

        assertEquals(2_000L, detail.refundedAmount());
        assertEquals(PAYMENT_AMOUNT, detail.paymentAmount());
        assertEquals(USER_ID, detail.customerUserId());
        assertEquals(OWNER_ID, detail.requesterUserId());
        assertEquals("COMPLETED", detail.preview().expected().status());
        assertEquals(PaymentStatus.APPROVED, detail.preview().expected().paymentStatus());
    }

    @Test
    void previewShowsInfoWhenLedgerLookupFails() {
        givenManualReviewPayment(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findPaymentTransaction(PAYMENT_ID))
                .thenThrow(new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE));

        PaymentReviewDetailResult detail = service.getPaymentReview(MASTER_ID, PAYMENT_ID);

        assertTrue(detail.preview().ledgerLookupFailed());
        assertNull(detail.preview().ledger());
        assertNull(detail.preview().expected());
        assertEquals(PAYMENT_ID, detail.paymentId());
        assertEquals(SettlementReviewReason.RETRY_EXHAUSTED, detail.reviewReason());
    }

    @Test
    void previewRejectsUnknownOrResolvedTargets() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());
        assertEquals(PaymentErrorCode.PAYMENT_NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.getPaymentReview(MASTER_ID, PAYMENT_ID)).getErrorCode());

        Refund refund = givenManualReviewRefund(SettlementReviewReason.RETRY_EXHAUSTED, PAYMENT_AMOUNT);
        ReflectionTestUtils.setField(refund, "status", RefundStatus.COMPLETED);
        ReflectionTestUtils.setField(refund, "reviewReason", null);
        assertEquals(PaymentErrorCode.NOT_MANUAL_REVIEW, assertThrows(BusinessException.class,
                () -> service.getRefundReview(MASTER_ID, REFUND_ID)).getErrorCode());

        verifyNoInteractions(ledgerClient);
    }

    // ── 목록 ──

    @Test
    void rejectsInvalidPage() {
        BusinessException negativePage = assertThrows(BusinessException.class,
                () -> service.getPaymentManualReviews(null, -1, 20));
        BusinessException zeroSize = assertThrows(BusinessException.class,
                () -> service.getRefundManualReviews(null, 0, 0));

        assertEquals(CommonErrorCode.INVALID_REQUEST, negativePage.getErrorCode());
        assertEquals(CommonErrorCode.INVALID_REQUEST, zeroSize.getErrorCode());
    }

    @Test
    void paymentListCapsPageSizeAndSortsOldestFirst() {
        when(paymentRepository.findManualReviews(any(), any())).thenReturn(Page.empty());

        service.getPaymentManualReviews(SettlementReviewReason.RETRY_EXHAUSTED, 1, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findManualReviews(eq(SettlementReviewReason.RETRY_EXHAUSTED), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")), captor.getValue().getSort());
    }

    @Test
    void refundListPassesUnsortedPageBecauseQueryFixesOrder() {
        when(refundRepository.findManualReviews(any(), any())).thenReturn(Page.empty());

        service.getRefundManualReviews(null, 2, 10);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository).findManualReviews(eq(null), captor.capture());
        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(10, captor.getValue().getPageSize());
        assertTrue(captor.getValue().getSort().isUnsorted());
    }

    // ── 처리 기록 목록 ──

    @Test
    void resolutionListPassesFiltersAndSortsNewestFirst() {
        when(resolutionRepository.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.getResolutions(ReviewTarget.REFUND, MASTER_ID, PAYMENT_ID, 1, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(resolutionRepository).search(eq(ReviewTarget.REFUND), eq(MASTER_ID), eq(PAYMENT_ID), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")), captor.getValue().getSort());
    }

    @Test
    void resolutionListRejectsInvalidPage() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getResolutions(null, null, null, -1, 20));

        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verifyNoInteractions(resolutionRepository);
    }

    // ── 준비 ──

    private Payment givenManualReviewPayment(SettlementReviewReason reviewReason) {
        Payment payment = newPayment();
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(payment, "reviewReason", reviewReason);
        ReflectionTestUtils.setField(payment, "attemptCount", 5);
        return payment;
    }

    private Payment givenRefundPendingPayment() {
        Payment payment = newPayment();
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.REFUND_PENDING);
        ReflectionTestUtils.setField(payment, "ledgerId", PAYMENT_TRANSACTION_ID);
        return payment;
    }

    private Payment newPayment() {
        Payment payment = Payment.create(USER_ID, WALLET_ID, "payment-key-1", MERCHANT_ID, "가맹점", "상품",
                CurrencyCode.KRW, PAYMENT_AMOUNT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        return payment;
    }

    private Refund givenManualReviewRefund(SettlementReviewReason reviewReason, long amount) {
        Refund refund = Refund.request(PAYMENT_ID, OWNER_ID, "refund-key-1", amount, CurrencyCode.KRW, "단순 변심");
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
        ReflectionTestUtils.setField(refund, "status", RefundStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(refund, "reviewReason", reviewReason);
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund));
        when(refundRepository.findAllByPaymentId(PAYMENT_ID)).thenReturn(List.of(refund));
        return refund;
    }

    // 조건부 업데이트가 DB에 반영된 것처럼 환불 상태를 바꿔 둠(남은 금액 계산이 COMPLETED 합계를 읽음)
    private void givenResolveCompletedAppliesTo(Refund refund) {
        when(refundRepository.resolveCompleted(eq(REFUND_ID), eq(REFUND_TRANSACTION_ID), any())).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(refund, "status", RefundStatus.COMPLETED);
            return 1;
        });
    }

    // 원장 POSTED: 결제 요청과 같은 항목(사용자 지갑 → 가맹점 원장 지갑, 결제 금액)
    private static PaymentLedgerLookup paymentPosted() {
        return paymentPosted(PAYMENT_AMOUNT);
    }

    private static PaymentLedgerLookup paymentPosted(long ledgerAmount) {
        return PaymentLedgerLookup.posted(PAYMENT_TRANSACTION_ID, List.of(
                new PaymentLedgerLookup.Entry(WALLET_ID, "KRW", "DEBIT", ledgerAmount, "PAYMENT_OUT"),
                new PaymentLedgerLookup.Entry(MERCHANT_LEDGER_WALLET_ID, "KRW", "CREDIT", ledgerAmount, "PAYMENT_IN")));
    }

    // 원장 POSTED: 환불 요청과 같은 항목(가맹점 원장 지갑 → 사용자 지갑, 환불 금액)
    private static PaymentLedgerLookup refundPosted(long ledgerAmount) {
        return PaymentLedgerLookup.posted(REFUND_TRANSACTION_ID, List.of(
                new PaymentLedgerLookup.Entry(MERCHANT_LEDGER_WALLET_ID, "KRW", "DEBIT", ledgerAmount,
                        "PAYMENT_REFUND_OUT"),
                new PaymentLedgerLookup.Entry(WALLET_ID, "KRW", "CREDIT", ledgerAmount, "PAYMENT_REFUND_IN")));
    }

    private static PaymentLedgerLookup paymentLookup(ReviewLedgerStatus status) {
        return switch (status) {
            case POSTED -> paymentPosted();
            case REJECTED -> PaymentLedgerLookup.rejected(PAYMENT_TRANSACTION_ID, "원장 거절 문구");
            case NOT_FOUND -> PaymentLedgerLookup.notFound();
        };
    }

    // 환불 금액은 결제 전액(PAYMENT_AMOUNT) 기준
    private static PaymentLedgerLookup refundLookup(ReviewLedgerStatus status) {
        return switch (status) {
            case POSTED -> refundPosted(PAYMENT_AMOUNT);
            case REJECTED -> PaymentLedgerLookup.rejected(REFUND_TRANSACTION_ID, "원장 거절 문구");
            case NOT_FOUND -> PaymentLedgerLookup.notFound();
        };
    }

    private static String ledgerText(ReviewLedgerStatus status) {
        return status == ReviewLedgerStatus.REJECTED ? "원장 거절 확인" : "원장 기록 없음";
    }

    private PaymentHistory savedHistory() {
        ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
        verify(paymentHistoryRepository).save(captor.capture());
        return captor.getValue();
    }

    private PaymentReviewResolution savedResolution() {
        ArgumentCaptor<PaymentReviewResolution> captor = ArgumentCaptor.forClass(PaymentReviewResolution.class);
        verify(resolutionRepository).save(captor.capture());
        return captor.getValue();
    }
}

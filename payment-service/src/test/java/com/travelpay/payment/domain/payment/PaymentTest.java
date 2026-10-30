package com.travelpay.payment.domain.payment;

import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_MERCHANT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID LEDGER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String KEY = "payment-key-1";
    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID OTHER_PAYMENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID REFUND_LEDGER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String REFUND_KEY = "refund-key-1";
    private static final String REFUND_REASON = "단순 변심";

    @Test
    void startsAsPendingWhenCreated() {
        Payment payment = payment(10_000L);

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(USER_ID, payment.getUserId());
        assertEquals(10_000L, payment.getAmount());
        assertEquals(CurrencyCode.KRW, payment.getCurrency());
    }

    @Test
    void rejectsZeroOrNegativeAmount() {
        BusinessException zero = assertThrows(BusinessException.class, () -> payment(0L));
        BusinessException negative = assertThrows(BusinessException.class, () -> payment(-1L));

        assertEquals(PaymentErrorCode.INVALID_AMOUNT, zero.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_AMOUNT, negative.getErrorCode());
    }

    @Test
    void createdHistoryRecordsPendingByUser() {
        PaymentHistory history = payment(10_000L).createdHistory();

        assertNull(history.getPreviousStatus());
        assertEquals(PaymentStatus.PENDING, history.getCurrentStatus());
        assertEquals(PaymentActor.USER, history.getCreatedBy());
    }

    @Test
    void approveChangesStatusAndRecordsLedgerId() {
        Payment payment = payment(10_000L);

        PaymentHistory history = payment.approvedHistory(LEDGER_ID);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(LEDGER_ID, payment.getLedgerId());
        assertEquals(PaymentStatus.PENDING, history.getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, history.getCurrentStatus());
        assertEquals(PaymentActor.SYSTEM, history.getCreatedBy());
    }

    @Test
    void declineChangesStatusAndRecordsReason() {
        Payment payment = payment(10_000L);

        PaymentHistory history = payment.declinedHistory("잔액이 부족합니다.");

        assertEquals(PaymentStatus.DECLINED, payment.getStatus());
        assertEquals("잔액이 부족합니다.", payment.getDeclineReason());
        assertEquals(PaymentStatus.PENDING, history.getPreviousStatus());
        assertEquals(PaymentStatus.DECLINED, history.getCurrentStatus());
        assertEquals("잔액이 부족합니다.", history.getReason());
        assertEquals(PaymentActor.SYSTEM, history.getCreatedBy());
    }

    @Test
    void rejectsStatusChangeWhenNotPending() {
        Payment approved = payment(10_000L);
        approved.approvedHistory(LEDGER_ID);

        BusinessException approveAgain = assertThrows(BusinessException.class, () -> approved.approvedHistory(LEDGER_ID));
        BusinessException declineAfterApprove = assertThrows(BusinessException.class, () -> approved.declinedHistory("사유"));

        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, approveAgain.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, declineAfterApprove.getErrorCode());
        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
    }

    @Test
    void refundRequestChangesApprovedToRefundPending() {
        Payment payment = approvedPayment();

        PaymentHistory history = payment.refundRequestedHistory("단순 변심");

        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        assertEquals(PaymentStatus.APPROVED, history.getPreviousStatus());
        assertEquals(PaymentStatus.REFUND_PENDING, history.getCurrentStatus());
        assertEquals("단순 변심", history.getReason());
        assertEquals(PaymentActor.USER, history.getCreatedBy());
    }

    @Test
    void refundCompletionChangesRefundPendingToRefunded() {
        Payment payment = approvedPayment();
        payment.refundRequestedHistory("단순 변심");

        PaymentHistory history = payment.refundedHistory();

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(LEDGER_ID, payment.getLedgerId());
        assertEquals(PaymentStatus.REFUND_PENDING, history.getPreviousStatus());
        assertEquals(PaymentStatus.REFUNDED, history.getCurrentStatus());
        assertEquals(PaymentActor.SYSTEM, history.getCreatedBy());
    }

    @Test
    void refundDeclineReturnsRefundPendingToApproved() {
        Payment payment = approvedPayment();
        payment.refundRequestedHistory("단순 변심");

        PaymentHistory history = payment.refundDeclinedHistory("원거래를 찾을 수 없습니다.");

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertNull(payment.getDeclineReason());
        assertEquals(PaymentStatus.REFUND_PENDING, history.getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, history.getCurrentStatus());
        assertEquals("원거래를 찾을 수 없습니다.", history.getReason());
        assertEquals(PaymentActor.SYSTEM, history.getCreatedBy());
    }

    @Test
    void refundCanBeRequestedAgainAfterDecline() {
        Payment payment = approvedPayment();
        payment.refundRequestedHistory("단순 변심");
        payment.refundDeclinedHistory("원거래를 찾을 수 없습니다.");

        payment.refundRequestedHistory("다시 요청");

        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
    }

    @Test
    void rejectsRefundRequestWithErrorMatchingStatus() {
        Payment refundPending = approvedPayment();
        refundPending.refundRequestedHistory("단순 변심");
        Payment refunded = approvedPayment();
        refunded.refundRequestedHistory("단순 변심");
        refunded.refundedHistory();
        Payment pending = payment(10_000L);
        Payment declined = payment(10_000L);
        declined.declinedHistory("잔액이 부족합니다.");

        BusinessException inProgress = assertThrows(BusinessException.class,
                () -> refundPending.refundRequestedHistory("사유"));
        BusinessException alreadyRefunded = assertThrows(BusinessException.class,
                () -> refunded.refundRequestedHistory("사유"));
        BusinessException fromPending = assertThrows(BusinessException.class,
                () -> pending.refundRequestedHistory("사유"));
        BusinessException fromDeclined = assertThrows(BusinessException.class,
                () -> declined.refundRequestedHistory("사유"));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, inProgress.getErrorCode());
        assertEquals(PaymentErrorCode.ALREADY_REFUNDED, alreadyRefunded.getErrorCode());
        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, fromPending.getErrorCode());
        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, fromDeclined.getErrorCode());
    }

    @Test
    void rejectsRefundCompletionOrDeclineUnlessRefundPending() {
        Payment approved = approvedPayment();

        BusinessException complete = assertThrows(BusinessException.class, approved::refundedHistory);
        BusinessException decline = assertThrows(BusinessException.class,
                () -> approved.refundDeclinedHistory("사유"));

        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, complete.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, decline.getErrorCode());
        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
    }

    @Test
    void sameRequestOnlyWhenMerchantCurrencyAndAmountMatch() {
        Payment payment = payment(10_000L);

        assertTrue(payment.isSameRequest(MERCHANT_ID, CurrencyCode.KRW, 10_000L));
        assertFalse(payment.isSameRequest(OTHER_MERCHANT_ID, CurrencyCode.KRW, 10_000L));
        assertFalse(payment.isSameRequest(MERCHANT_ID, CurrencyCode.JPY, 10_000L));
        assertFalse(payment.isSameRequest(MERCHANT_ID, CurrencyCode.KRW, 50_000L));
    }

    @Test
    void ownedOnlyByRequestingUser() {
        Payment payment = payment(10_000L);

        assertTrue(payment.isOwnedBy(USER_ID));
        assertFalse(payment.isOwnedBy(OTHER_USER_ID));
    }

    // ---------- 환불 내역 ----------

    @Test
    void refundRecordStartsAsRequested() {
        Refund refund = refund(10_000L);

        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
        assertEquals(PAYMENT_ID, refund.getPaymentId());
        assertEquals(USER_ID, refund.getUserId());
        assertEquals(REFUND_KEY, refund.getIdempotencyKey());
        assertEquals(10_000L, refund.getAmount());
        assertEquals(CurrencyCode.KRW, refund.getCurrency());
        assertEquals(REFUND_REASON, refund.getReason());
        assertNull(refund.getLedgerId());
        assertNull(refund.getDeclineReason());
    }

    @Test
    void refundRecordRejectsZeroOrNegativeAmount() {
        BusinessException zero = assertThrows(BusinessException.class, () -> refund(0L));
        BusinessException negative = assertThrows(BusinessException.class, () -> refund(-1L));

        assertEquals(PaymentErrorCode.INVALID_AMOUNT, zero.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_AMOUNT, negative.getErrorCode());
    }

    @Test
    void refundRecordCompleteChangesStatusAndRecordsLedgerId() {
        Refund refund = refund(10_000L);

        refund.complete(REFUND_LEDGER_ID);

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertEquals(REFUND_LEDGER_ID, refund.getLedgerId());
    }

    @Test
    void refundRecordDeclineChangesStatusAndRecordsReason() {
        Refund refund = refund(10_000L);

        refund.decline("원거래를 찾을 수 없습니다.");

        assertEquals(RefundStatus.DECLINED, refund.getStatus());
        assertEquals("원거래를 찾을 수 없습니다.", refund.getDeclineReason());
        assertNull(refund.getLedgerId());
    }

    @Test
    void refundRecordRejectsStatusChangeUnlessRequested() {
        Refund completed = refund(10_000L);
        completed.complete(REFUND_LEDGER_ID);

        BusinessException completeAgain = assertThrows(BusinessException.class,
                () -> completed.complete(REFUND_LEDGER_ID));
        BusinessException declineAfterComplete = assertThrows(BusinessException.class,
                () -> completed.decline("사유"));

        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, completeAgain.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, declineAfterComplete.getErrorCode());
        assertEquals(RefundStatus.COMPLETED, completed.getStatus());
    }

    @Test
    void refundRecordSameRequestOnlyForSamePayment() {
        Refund refund = refund(10_000L);

        assertTrue(refund.isSameRequest(PAYMENT_ID));
        assertFalse(refund.isSameRequest(OTHER_PAYMENT_ID));
    }

    private Payment payment(long amount) {
        return Payment.create(USER_ID, WALLET_ID, KEY, MERCHANT_ID, "테스트 가맹점", "테스트 주문",
                CurrencyCode.KRW, amount);
    }

    private Payment approvedPayment() {
        Payment payment = payment(10_000L);
        payment.approvedHistory(LEDGER_ID);
        return payment;
    }

    private Refund refund(long amount) {
        return Refund.request(PAYMENT_ID, USER_ID, REFUND_KEY, amount, CurrencyCode.KRW, REFUND_REASON);
    }
}

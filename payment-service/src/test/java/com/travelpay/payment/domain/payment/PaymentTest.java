package com.travelpay.payment.domain.payment;

import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    // MASTER는 고객 신청 없이 승인된 결제를 바로 환불할 수 있습니다.
    @Test
    void masterRefundChangesApprovedToRefundPending() {
        Payment payment = approvedPayment();

        PaymentHistory history = payment.refundStartedHistory("단순 변심", PaymentActor.MASTER);

        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        assertEquals(PaymentStatus.APPROVED, history.getPreviousStatus());
        assertEquals(PaymentStatus.REFUND_PENDING, history.getCurrentStatus());
        assertEquals("단순 변심", history.getReason());
        assertEquals(PaymentActor.MASTER, history.getCreatedBy());
    }

    @Test
    void merchantCannotRefundWithoutCustomerRequest() {
        Payment payment = approvedPayment();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> payment.refundStartedHistory("단순 변심", PaymentActor.MERCHANT));

        assertEquals(PaymentErrorCode.REFUND_NOT_REQUESTED, exception.getErrorCode());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
    }

    @Test
    void refundCompletionChangesRefundPendingToRefunded() {
        Payment payment = approvedPayment();
        payment.refundStartedHistory("단순 변심", PaymentActor.MASTER);

        PaymentHistory history = payment.refundCompletedHistory(10_000L, 0L);

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(LEDGER_ID, payment.getLedgerId());
        assertEquals(PaymentStatus.REFUND_PENDING, history.getPreviousStatus());
        assertEquals(PaymentStatus.REFUNDED, history.getCurrentStatus());
        assertEquals(PaymentActor.SYSTEM, history.getCreatedBy());
    }

    @Test
    void partialRefundCompletionReturnsRefundPendingToApproved() {
        Payment payment = approvedPayment();
        payment.refundStartedHistory("일부 취소", PaymentActor.MASTER);

        PaymentHistory history = payment.refundCompletedHistory(3_000L, 7_000L);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(PaymentStatus.REFUND_PENDING, history.getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, history.getCurrentStatus());
        assertEquals("부분 환불 완료: 3000", history.getReason());
        assertEquals(PaymentActor.SYSTEM, history.getCreatedBy());
    }

    @Test
    void refundRequestRecordsGivenActor() {
        Payment payment = approvedPayment();

        PaymentHistory history = payment.refundStartedHistory("관리자 처리", PaymentActor.MASTER);

        assertEquals(PaymentActor.MASTER, history.getCreatedBy());
    }

    @Test
    void refundDeclineReturnsRefundPendingToApproved() {
        Payment payment = approvedPayment();
        payment.refundStartedHistory("단순 변심", PaymentActor.MASTER);

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
        payment.refundStartedHistory("단순 변심", PaymentActor.MASTER);
        payment.refundDeclinedHistory("원거래를 찾을 수 없습니다.");

        payment.refundStartedHistory("다시 요청", PaymentActor.MASTER);

        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
    }

    @Test
    void rejectsRefundRequestWithErrorMatchingStatus() {
        Payment refundPending = approvedPayment();
        refundPending.refundStartedHistory("단순 변심", PaymentActor.MASTER);
        Payment refunded = approvedPayment();
        refunded.refundStartedHistory("단순 변심", PaymentActor.MASTER);
        refunded.refundCompletedHistory(10_000L, 0L);
        Payment pending = payment(10_000L);
        Payment declined = payment(10_000L);
        declined.declinedHistory("잔액이 부족합니다.");

        BusinessException inProgress = assertThrows(BusinessException.class,
                () -> refundPending.refundStartedHistory("사유", PaymentActor.MASTER));
        BusinessException alreadyRefunded = assertThrows(BusinessException.class,
                () -> refunded.refundStartedHistory("사유", PaymentActor.MASTER));
        BusinessException fromPending = assertThrows(BusinessException.class,
                () -> pending.refundStartedHistory("사유", PaymentActor.MASTER));
        BusinessException fromDeclined = assertThrows(BusinessException.class,
                () -> declined.refundStartedHistory("사유", PaymentActor.MASTER));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, inProgress.getErrorCode());
        assertEquals(PaymentErrorCode.ALREADY_REFUNDED, alreadyRefunded.getErrorCode());
        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, fromPending.getErrorCode());
        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, fromDeclined.getErrorCode());
    }

    @Test
    void rejectsRefundCompletionOrDeclineUnlessRefundPending() {
        Payment approved = approvedPayment();

        BusinessException complete = assertThrows(BusinessException.class, () -> approved.refundCompletedHistory(10_000L, 0L));
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

        assertTrue(refund.isSameRequest(PAYMENT_ID, 10_000L));
        assertFalse(refund.isSameRequest(OTHER_PAYMENT_ID, 10_000L));
        assertFalse(refund.isSameRequest(PAYMENT_ID, 3_000L));
    }

    @Test
    void resolveApprovedMovesManualReviewToApprovedByMaster() {
        Payment payment = manualReviewPayment();

        PaymentHistory history = payment.resolveApprovedHistory(LEDGER_ID);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(LEDGER_ID, payment.getLedgerId());
        assertNull(payment.getReviewReason());
        assertEquals(PaymentStatus.MANUAL_REVIEW, history.getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, history.getCurrentStatus());
        assertEquals("원장에 거래가 반영되어 있음", history.getReason());
        assertEquals(PaymentActor.MASTER, history.getCreatedBy());
    }

    @Test
    void resolveDeclinedMovesManualReviewToDeclinedByMaster() {
        Payment payment = manualReviewPayment();

        PaymentHistory history = payment.resolveDeclinedHistory("결제 처리 시간이 초과되어 취소되었습니다.");

        assertEquals(PaymentStatus.DECLINED, payment.getStatus());
        assertEquals("결제 처리 시간이 초과되어 취소되었습니다.", payment.getDeclineReason());
        assertNull(payment.getReviewReason());
        assertEquals(PaymentStatus.MANUAL_REVIEW, history.getPreviousStatus());
        assertEquals(PaymentStatus.DECLINED, history.getCurrentStatus());
        assertEquals("결제 처리 시간이 초과되어 취소되었습니다.", history.getReason());
        assertEquals(PaymentActor.MASTER, history.getCreatedBy());
    }

    @Test
    void resolveRejectsPaymentNotInManualReview() {
        Payment pending = payment(10_000L);
        Payment approved = approvedPayment();

        BusinessException approve = assertThrows(BusinessException.class, () -> pending.resolveApprovedHistory(LEDGER_ID));
        BusinessException decline = assertThrows(BusinessException.class, () -> approved.resolveDeclinedHistory("x"));

        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, approve.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, decline.getErrorCode());
        assertEquals(PaymentStatus.PENDING, pending.getStatus());
        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
    }

    @Test
    void refundOutcomeHistoriesRecordGivenActor() {
        Payment completed = approvedPayment();
        completed.refundStartedHistory("단순 변심", PaymentActor.MASTER);
        Payment declined = approvedPayment();
        declined.refundStartedHistory("단순 변심", PaymentActor.MASTER);

        assertEquals(PaymentActor.MASTER,
                completed.refundCompletedHistory(10_000L, 0L, PaymentActor.MASTER).getCreatedBy());
        assertEquals(PaymentActor.MASTER,
                declined.refundDeclinedHistory("환불 처리 중 오류로 취소되었습니다.", PaymentActor.MASTER).getCreatedBy());
    }

    @Test
    void customerRefundRequestChangesApprovedToRefundRequestedByUser() {
        Payment payment = approvedPayment();

        PaymentHistory history = payment.refundRequestedByCustomerHistory("사이즈가 맞지 않음");

        assertEquals(PaymentStatus.REFUND_REQUESTED, payment.getStatus());
        assertEquals(PaymentStatus.APPROVED, history.getPreviousStatus());
        assertEquals(PaymentStatus.REFUND_REQUESTED, history.getCurrentStatus());
        assertEquals("사이즈가 맞지 않음", history.getReason());
        assertEquals(PaymentActor.USER, history.getCreatedBy());
    }

    @Test
    void customerRefundRequestRequiresReason() {
        Payment payment = approvedPayment();

        assertThrows(NullPointerException.class, () -> payment.refundRequestedByCustomerHistory(null));
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
    }

    @Test
    void rejectsCustomerRefundRequestWithErrorMatchingStatus() {
        Payment refundPending = approvedPayment();
        refundPending.refundStartedHistory("단순 변심", PaymentActor.MASTER);
        Payment refunded = approvedPayment();
        refunded.refundStartedHistory("단순 변심", PaymentActor.MASTER);
        refunded.refundCompletedHistory(10_000L, 0L);
        Payment pending = payment(10_000L);

        BusinessException inProgress = assertThrows(BusinessException.class,
                () -> refundPending.refundRequestedByCustomerHistory("사유"));
        BusinessException alreadyRefunded = assertThrows(BusinessException.class,
                () -> refunded.refundRequestedByCustomerHistory("사유"));
        BusinessException fromPending = assertThrows(BusinessException.class,
                () -> pending.refundRequestedByCustomerHistory("사유"));

        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, inProgress.getErrorCode());
        assertEquals(PaymentErrorCode.ALREADY_REFUNDED, alreadyRefunded.getErrorCode());
        assertEquals(PaymentErrorCode.NOT_REFUNDABLE, fromPending.getErrorCode());
    }

    // 이미 신청한 결제의 재신청은 서비스에서 현재 상태로 응답합니다. 도메인은 상태를 바꾸지 않습니다.
    @Test
    void rejectsCustomerRefundRequestWhenAlreadyRequested() {
        Payment payment = refundRequestedPayment();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> payment.refundRequestedByCustomerHistory("사유"));

        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, exception.getErrorCode());
        assertEquals(PaymentStatus.REFUND_REQUESTED, payment.getStatus());
    }

    @Test
    void customerCancelReturnsRefundRequestedToApprovedByUser() {
        Payment payment = refundRequestedPayment();

        PaymentHistory history = payment.refundRequestCancelledHistory();

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(PaymentStatus.REFUND_REQUESTED, history.getPreviousStatus());
        assertEquals(PaymentStatus.APPROVED, history.getCurrentStatus());
        assertNull(history.getReason());
        assertEquals(PaymentActor.USER, history.getCreatedBy());
    }

    @Test
    void rejectReturnsRefundRequestedToApprovedWithReasonAndActor() {
        Payment byMerchant = refundRequestedPayment();
        Payment byMaster = refundRequestedPayment();

        PaymentHistory merchantHistory = byMerchant.refundRequestRejectedHistory("사용 흔적이 있음", PaymentActor.MERCHANT);
        PaymentHistory masterHistory = byMaster.refundRequestRejectedHistory("고객센터 확인 결과 환불 대상 아님", PaymentActor.MASTER);

        assertEquals(PaymentStatus.APPROVED, byMerchant.getStatus());
        assertEquals(PaymentStatus.REFUND_REQUESTED, merchantHistory.getPreviousStatus());
        assertEquals("사용 흔적이 있음", merchantHistory.getReason());
        assertEquals(PaymentActor.MERCHANT, merchantHistory.getCreatedBy());
        assertEquals(PaymentStatus.APPROVED, byMaster.getStatus());
        assertEquals(PaymentActor.MASTER, masterHistory.getCreatedBy());
    }

    @Test
    void rejectRequiresReason() {
        Payment payment = refundRequestedPayment();

        assertThrows(NullPointerException.class,
                () -> payment.refundRequestRejectedHistory(null, PaymentActor.MERCHANT));
        assertEquals(PaymentStatus.REFUND_REQUESTED, payment.getStatus());
    }

    @Test
    void rejectsCancelOrRejectUnlessRefundRequested() {
        Payment approved = approvedPayment();
        Payment refundPending = refundRequestedPayment();
        refundPending.refundStartedHistory("단순 변심", PaymentActor.MERCHANT);

        BusinessException cancelApproved = assertThrows(BusinessException.class,
                approved::refundRequestCancelledHistory);
        BusinessException rejectApproved = assertThrows(BusinessException.class,
                () -> approved.refundRequestRejectedHistory("사유", PaymentActor.MERCHANT));
        BusinessException cancelInProgress = assertThrows(BusinessException.class,
                refundPending::refundRequestCancelledHistory);
        BusinessException rejectInProgress = assertThrows(BusinessException.class,
                () -> refundPending.refundRequestRejectedHistory("사유", PaymentActor.MERCHANT));

        assertEquals(PaymentErrorCode.REFUND_NOT_REQUESTED, cancelApproved.getErrorCode());
        assertEquals(PaymentErrorCode.REFUND_NOT_REQUESTED, rejectApproved.getErrorCode());
        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, cancelInProgress.getErrorCode());
        assertEquals(PaymentErrorCode.REFUND_IN_PROGRESS, rejectInProgress.getErrorCode());
    }

    @Test
    void refundStartsFromCustomerRefundRequest() {
        Payment payment = refundRequestedPayment();

        PaymentHistory history = payment.refundStartedHistory("단순 변심", PaymentActor.MERCHANT);

        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        assertEquals(PaymentStatus.REFUND_REQUESTED, history.getPreviousStatus());
        assertEquals(PaymentActor.MERCHANT, history.getCreatedBy());
    }

    @Test
    void customerCanRequestAgainAfterCancelOrReject() {
        Payment cancelled = refundRequestedPayment();
        cancelled.refundRequestCancelledHistory();
        Payment rejected = refundRequestedPayment();
        rejected.refundRequestRejectedHistory("사용 흔적이 있음", PaymentActor.MERCHANT);

        cancelled.refundRequestedByCustomerHistory("다시 신청");
        rejected.refundRequestedByCustomerHistory("다시 신청");

        assertEquals(PaymentStatus.REFUND_REQUESTED, cancelled.getStatus());
        assertEquals(PaymentStatus.REFUND_REQUESTED, rejected.getStatus());
    }

    private Payment refundRequestedPayment() {
        Payment payment = approvedPayment();
        payment.refundRequestedByCustomerHistory("단순 변심");
        return payment;
    }

    private Payment manualReviewPayment() {
        Payment payment = payment(10_000L);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(payment, "reviewReason", SettlementReviewReason.RETRY_EXHAUSTED);
        return payment;
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

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

    private Payment payment(long amount) {
        return Payment.create(USER_ID, WALLET_ID, KEY, MERCHANT_ID, "테스트 가맹점", "테스트 주문",
                CurrencyCode.KRW, amount);
    }
}

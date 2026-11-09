package com.travelpay.payment.domain.merchant;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementTest {

    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_MERCHANT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OWNER_WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID SETTLEMENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String KEY = "settlement-key-1";
    private static final Instant CUTOFF = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void requestStartsAsRequested() {
        Settlement settlement = request(15_000L);

        assertEquals(SettlementStatus.REQUESTED, settlement.getStatus());
        assertEquals(MERCHANT_ID, settlement.getMerchantId());
        assertEquals(OWNER_ID, settlement.getRequestedBy());
        assertEquals(OWNER_WALLET_ID, settlement.getOwnerWalletId());
        assertEquals(CurrencyCode.KRW, settlement.getCurrency());
        assertEquals(15_000L, settlement.getAmount());
        assertEquals(CUTOFF, settlement.getCutoffAt());
        assertEquals(0, settlement.getAttemptCount());
        assertNull(settlement.getLedgerTransactionId());
    }

    @Test
    void rejectsNonPositiveAmount() {
        BusinessException zero = assertThrows(BusinessException.class, () -> request(0L));
        BusinessException negative = assertThrows(BusinessException.class, () -> request(-1L));

        assertEquals(PaymentErrorCode.INVALID_AMOUNT, zero.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_AMOUNT, negative.getErrorCode());
    }

    @Test
    void paidStoresLedgerTransaction() {
        Settlement settlement = request(15_000L);

        settlement.paid(LEDGER_TRANSACTION_ID);

        assertEquals(SettlementStatus.PAID, settlement.getStatus());
        assertEquals(LEDGER_TRANSACTION_ID, settlement.getLedgerTransactionId());
    }

    @Test
    void failStoresReason() {
        Settlement settlement = request(15_000L);

        settlement.fail("사용할 수 없는 지갑입니다.");

        assertEquals(SettlementStatus.FAILED, settlement.getStatus());
        assertEquals("사용할 수 없는 지갑입니다.", settlement.getFailureReason());
    }

    @Test
    void resultCanBeAppliedOnlyOnce() {
        Settlement paid = request(15_000L);
        paid.paid(LEDGER_TRANSACTION_ID);
        Settlement failed = request(15_000L);
        failed.fail("거절");

        BusinessException paidAgain = assertThrows(BusinessException.class, () -> paid.fail("거절"));
        BusinessException failedAgain = assertThrows(BusinessException.class,
                () -> failed.paid(LEDGER_TRANSACTION_ID));

        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, paidAgain.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, failedAgain.getErrorCode());
        assertEquals(SettlementStatus.PAID, paid.getStatus());
        assertEquals(SettlementStatus.FAILED, failed.getStatus());
    }

    @Test
    void operatorResolutionOnlyAppliesToManualReviewAndClearsReason() {
        Settlement paid = manualReview();
        paid.resolvePaid(LEDGER_TRANSACTION_ID);
        Settlement failed = manualReview();
        failed.resolveFailed("정산 처리 시간이 초과되어 취소되었습니다.");

        assertEquals(SettlementStatus.PAID, paid.getStatus());
        assertEquals(LEDGER_TRANSACTION_ID, paid.getLedgerTransactionId());
        assertNull(paid.getReviewReason());
        assertEquals(SettlementStatus.FAILED, failed.getStatus());
        assertEquals("정산 처리 시간이 초과되어 취소되었습니다.", failed.getFailureReason());
        assertNull(failed.getReviewReason());

        // REQUESTED 정산은 운영자 처리 대상이 아니고, MANUAL_REVIEW 정산은 자동 반영 대상이 아님
        BusinessException requested = assertThrows(BusinessException.class,
                () -> request(15_000L).resolvePaid(LEDGER_TRANSACTION_ID));
        BusinessException manual = assertThrows(BusinessException.class,
                () -> manualReview().paid(LEDGER_TRANSACTION_ID));
        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, requested.getErrorCode());
        assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, manual.getErrorCode());
    }

    @Test
    void sameRequestComparesMerchantOnly() {
        Settlement settlement = request(15_000L);

        assertTrue(settlement.isSameRequest(MERCHANT_ID));
        assertFalse(settlement.isSameRequest(OTHER_MERCHANT_ID));
    }

    @Test
    void itemRequiresPositiveAmount() {
        SettlementItem item = SettlementItem.of(PAYMENT_ID, SETTLEMENT_ID, 7_000L);
        BusinessException zero = assertThrows(BusinessException.class,
                () -> SettlementItem.of(PAYMENT_ID, SETTLEMENT_ID, 0L));

        assertEquals(PAYMENT_ID, item.getId());
        assertEquals(SETTLEMENT_ID, item.getSettlementId());
        assertEquals(7_000L, item.getAmount());
        assertTrue(item.isNew());
        assertEquals(PaymentErrorCode.INVALID_AMOUNT, zero.getErrorCode());
    }

    private static Settlement request(long amount) {
        return Settlement.request(MERCHANT_ID, OWNER_ID, KEY, OWNER_ID, OWNER_WALLET_ID,
                CurrencyCode.KRW, amount, CUTOFF);
    }

    // MANUAL_REVIEW는 저장소의 조건부 UPDATE로만 들어가므로 테스트에서는 필드를 직접 맞춤
    private static Settlement manualReview() {
        Settlement settlement = request(15_000L);
        ReflectionTestUtils.setField(settlement, "status", SettlementStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(settlement, "reviewReason", SettlementReviewReason.RETRY_EXHAUSTED);
        return settlement;
    }
}

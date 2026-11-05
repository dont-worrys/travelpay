package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.domain.payment.PaymentStatus;

/**
 * 단건 조회에서 보여 주는 원장 조회 결과와 예상 처리 결과입니다. 원장 조회에 실패하면 둘 다 없습니다.
 * expected.resolvable이 false면 원장 보정이 필요해 처리할 수 없습니다(P5·R5).
 */
public record ManualReviewPreview(PaymentLedgerLookup ledger, Expected expected) {

    /** status: 처리 대상(결제 또는 환불)의 바뀔 상태, paymentStatus: 처리 후 결제 상태 */
    public record Expected(boolean resolvable, String status, PaymentStatus paymentStatus, String description) {
    }

    public static ManualReviewPreview lookupFailed() {
        return new ManualReviewPreview(null, null);
    }

    public boolean ledgerLookupFailed() {
        return ledger == null;
    }
}

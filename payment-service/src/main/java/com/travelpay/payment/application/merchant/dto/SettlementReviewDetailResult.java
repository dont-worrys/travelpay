package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementStatus;

/**
 * MANUAL_REVIEW 정산 단건: 정산 정보, 포함된 결제 수, 원장 조회 결과, 예상 처리 결과.
 * 원장 조회에 실패하면 ledger·expected가 없습니다. expected.resolvable이 false면 원장 보정이 필요해 처리할 수 없습니다.
 */
public record SettlementReviewDetailResult(SettlementManualReviewResult settlement, int paymentCount,
                                           PaymentLedgerLookup ledger, Expected expected) {

    /** status: 처리하면 바뀔 정산 상태(PAID·FAILED), resolvable이 false면 null */
    public record Expected(boolean resolvable, SettlementStatus status, String description) {
    }

    public static SettlementReviewDetailResult of(Settlement settlement, int paymentCount,
                                                  PaymentLedgerLookup ledger, Expected expected) {
        return new SettlementReviewDetailResult(SettlementManualReviewResult.from(settlement), paymentCount,
                ledger, expected);
    }

    public boolean ledgerLookupFailed() {
        return ledger == null;
    }
}

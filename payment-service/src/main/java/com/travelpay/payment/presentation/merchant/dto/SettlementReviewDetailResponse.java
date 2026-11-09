package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementReviewDetailResult;

import java.util.UUID;

/**
 * MANUAL_REVIEW 정산 단건. 원장 조회에 실패하면 ledgerLookupFailed가 true이고 ledger·expected는 null입니다.
 * expected.resolvable이 false면 원장 보정이 필요해 처리할 수 없습니다(그때 expected.status는 null).
 */
public record SettlementReviewDetailResponse(
        SettlementManualReviewResponse settlement,
        int paymentCount,
        boolean ledgerLookupFailed,
        Ledger ledger,
        Expected expected
) {
    /** declineReason은 원장이 거절했을 때만 있습니다 */
    public record Ledger(String status, UUID transactionId, String declineReason) {
    }

    public record Expected(boolean resolvable, String status, String description) {
    }

    public static SettlementReviewDetailResponse from(SettlementReviewDetailResult result) {
        Ledger ledger = result.ledger() == null ? null
                : new Ledger(result.ledger().status().name(), result.ledger().transactionId(),
                result.ledger().declineReason());
        Expected expected = result.expected() == null ? null
                : new Expected(result.expected().resolvable(),
                result.expected().status() == null ? null : result.expected().status().name(),
                result.expected().description());
        return new SettlementReviewDetailResponse(SettlementManualReviewResponse.from(result.settlement()),
                result.paymentCount(), result.ledgerLookupFailed(), ledger, expected);
    }
}

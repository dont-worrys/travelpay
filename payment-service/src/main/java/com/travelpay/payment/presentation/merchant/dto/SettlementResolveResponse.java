package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementResolveResult;

import java.util.UUID;

/** status: 처리 후 정산 상태(PAID·FAILED). failureReason은 FAILED일 때만, ledgerTransactionId는 원장 기록이 있을 때만 있습니다 */
public record SettlementResolveResponse(
        UUID settlementId,
        String status,
        String failureReason,
        String ledgerStatus,
        UUID ledgerTransactionId
) {
    public static SettlementResolveResponse from(SettlementResolveResult result) {
        return new SettlementResolveResponse(result.settlementId(), result.status().name(), result.failureReason(),
                result.ledgerStatus().name(), result.ledgerTransactionId());
    }
}

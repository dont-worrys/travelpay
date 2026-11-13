package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementResult;

import java.time.Instant;
import java.util.UUID;

/** 정산. merchantName은 가맹점 이름, paidAt은 PAID를 확정한 시각(입금 시각)이며 PAID가 아니면 없음 */
public record SettlementResponse(
        UUID settlementId,
        UUID merchantId,
        String merchantName,
        String status,
        Long amount,
        String currency,
        UUID ownerUserId,
        int paymentCount,
        Instant cutoffAt,
        UUID ledgerTransactionId,
        String failureReason,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static SettlementResponse from(SettlementResult result) {
        return new SettlementResponse(
                result.settlementId(), result.merchantId(), result.merchantName(), result.status().name(),
                result.amount(),
                result.currency().name(), result.ownerUserId(), result.paymentCount(), result.cutoffAt(),
                result.ledgerTransactionId(), result.failureReason(), result.paidAt(),
                result.createdAt(), result.updatedAt());
    }
}

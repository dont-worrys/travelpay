package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementResult;

import java.time.Instant;
import java.util.UUID;

public record SettlementResponse(
        UUID settlementId,
        UUID merchantId,
        String status,
        Long amount,
        String currency,
        UUID ownerUserId,
        int paymentCount,
        Instant cutoffAt,
        UUID ledgerTransactionId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static SettlementResponse from(SettlementResult result) {
        return new SettlementResponse(
                result.settlementId(), result.merchantId(), result.status().name(), result.amount(),
                result.currency().name(), result.ownerUserId(), result.paymentCount(), result.cutoffAt(),
                result.ledgerTransactionId(), result.failureReason(), result.createdAt(), result.updatedAt());
    }
}

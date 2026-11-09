package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementManualReviewResult;

import java.time.Instant;
import java.util.UUID;

public record SettlementManualReviewResponse(
        UUID settlementId,
        UUID merchantId,
        UUID ownerUserId,
        Long amount,
        String currency,
        String reviewReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static SettlementManualReviewResponse from(SettlementManualReviewResult result) {
        return new SettlementManualReviewResponse(
                result.settlementId(), result.merchantId(), result.ownerUserId(), result.amount(),
                result.currency().name(), result.reviewReason().name(), result.attemptCount(),
                result.createdAt(), result.updatedAt());
    }
}

package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.RefundResult;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID refundId,
        String status,
        Long amount,
        String currency,
        String reason,
        String declineReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundResponse from(RefundResult result) {
        return new RefundResponse(
                result.refundId(), result.status().name(), result.amount(), result.currency().name(),
                result.reason(), result.declineReason(), result.createdAt(), result.updatedAt());
    }
}

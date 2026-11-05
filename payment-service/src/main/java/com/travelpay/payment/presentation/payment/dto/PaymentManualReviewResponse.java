package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.PaymentManualReviewResult;

import java.time.Instant;
import java.util.UUID;

public record PaymentManualReviewResponse(
        UUID paymentId,
        UUID userId,
        UUID merchantId,
        Long amount,
        String currency,
        String reviewReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentManualReviewResponse from(PaymentManualReviewResult result) {
        return new PaymentManualReviewResponse(
                result.paymentId(), result.userId(), result.merchantId(), result.amount(),
                result.currency().name(), result.reviewReason().name(), result.attemptCount(),
                result.createdAt(), result.updatedAt());
    }
}

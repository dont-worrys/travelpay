package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.PaymentReviewDetailResult;

import java.time.Instant;
import java.util.UUID;

public record PaymentReviewDetailResponse(
        UUID paymentId,
        UUID userId,
        UUID merchantId,
        String merchantName,
        Long amount,
        String currency,
        String reviewReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        boolean ledgerLookupFailed,
        ManualReviewPreviewResponse.Ledger ledger,
        ManualReviewPreviewResponse.Expected expected
) {
    public static PaymentReviewDetailResponse from(PaymentReviewDetailResult result) {
        return new PaymentReviewDetailResponse(
                result.paymentId(), result.userId(), result.merchantId(), result.merchantName(), result.amount(),
                result.currency().name(), result.reviewReason().name(), result.attemptCount(),
                result.createdAt(), result.updatedAt(), result.preview().ledgerLookupFailed(),
                ManualReviewPreviewResponse.ledger(result.preview()),
                ManualReviewPreviewResponse.expected(result.preview()));
    }
}

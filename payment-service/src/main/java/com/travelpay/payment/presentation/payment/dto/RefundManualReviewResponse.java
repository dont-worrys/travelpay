package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.domain.payment.RefundManualReview;

import java.time.Instant;
import java.util.UUID;

/** customerUserId: 결제한 고객, requesterUserId: 환불을 실행한 사람(가맹점 주인 또는 MASTER) */
public record RefundManualReviewResponse(
        UUID refundId,
        UUID paymentId,
        UUID customerUserId,
        UUID merchantId,
        UUID requesterUserId,
        Long amount,
        String currency,
        String reviewReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundManualReviewResponse from(RefundManualReview review) {
        return new RefundManualReviewResponse(
                review.refundId(), review.paymentId(), review.customerUserId(), review.merchantId(),
                review.requesterUserId(), review.amount(), review.currency().name(), review.reviewReason().name(),
                review.attemptCount(), review.createdAt(), review.updatedAt());
    }
}

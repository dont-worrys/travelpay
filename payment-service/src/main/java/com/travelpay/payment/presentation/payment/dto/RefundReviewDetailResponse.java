package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.RefundReviewDetailResult;

import java.time.Instant;
import java.util.UUID;

/** customerUserId: 결제한 고객, requesterUserId: 환불을 실행한 사람, refundedAmount: 이 환불을 뺀 완료 환불 합계 */
public record RefundReviewDetailResponse(
        UUID refundId,
        UUID paymentId,
        UUID customerUserId,
        UUID merchantId,
        UUID requesterUserId,
        Long amount,
        String currency,
        Long paymentAmount,
        long refundedAmount,
        String reviewReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        boolean ledgerLookupFailed,
        ManualReviewPreviewResponse.Ledger ledger,
        ManualReviewPreviewResponse.Expected expected
) {
    public static RefundReviewDetailResponse from(RefundReviewDetailResult result) {
        return new RefundReviewDetailResponse(
                result.refundId(), result.paymentId(), result.customerUserId(), result.merchantId(),
                result.requesterUserId(), result.amount(), result.currency().name(), result.paymentAmount(),
                result.refundedAmount(), result.reviewReason().name(), result.attemptCount(),
                result.createdAt(), result.updatedAt(), result.preview().ledgerLookupFailed(),
                ManualReviewPreviewResponse.ledger(result.preview()),
                ManualReviewPreviewResponse.expected(result.preview()));
    }
}

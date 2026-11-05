package com.travelpay.payment.domain.payment;

import java.time.Instant;
import java.util.UUID;

/** MANUAL_REVIEW 환불 목록 한 줄. 고객·가맹점은 결제에서, 요청자(가맹점 주인 또는 MASTER)는 환불에서 가져옵니다. */
public record RefundManualReview(UUID refundId, UUID paymentId, UUID customerUserId, UUID merchantId,
                                 UUID requesterUserId, Long amount, CurrencyCode currency,
                                 SettlementReviewReason reviewReason, int attemptCount,
                                 Instant createdAt, Instant updatedAt) {
}

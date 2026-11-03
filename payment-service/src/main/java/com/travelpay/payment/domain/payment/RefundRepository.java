package com.travelpay.payment.domain.payment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    List<Refund> findAllByPaymentId(UUID paymentId);
    List<Refund> findAllByPaymentIdIn(Collection<UUID> paymentIds);
    List<Refund> findRecoveryTargets(Instant now, int limit);
    int claimForRecovery(UUID refundId, Instant seenUpdatedAt, Instant now);
    int recordUnknownAttempt(UUID refundId, int expectedAttempts, RefundStatus status,
                             SettlementReviewReason reviewReason, Instant now);
    int markManualReview(UUID refundId, SettlementReviewReason reviewReason, Instant now);
    long countByStatus(RefundStatus status);
    long countRequestedCreatedBefore(Instant before);
    List<UUID> findRequestedIdsCreatedBefore(Instant before, int limit);
}

package com.travelpay.payment.domain.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    Payment save(Payment payment);
    Optional<Payment> findById(UUID paymentId);
    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    Page<Payment> findPageByUserId(UUID userId, PaymentStatus status, Pageable pageable);
    Page<Payment> findPageByMerchantId(UUID merchantId, PaymentStatus status, Pageable pageable);
    Page<Payment> findManualReviews(SettlementReviewReason reviewReason, Pageable pageable);
    List<Payment> findRecoveryTargets(Instant now, int limit);
    int claimForRecovery(UUID paymentId, Instant seenUpdatedAt, Instant now);
    int recordUnknownAttempt(UUID paymentId, int expectedAttempts, PaymentStatus status,
                             SettlementReviewReason reviewReason, Instant now);
    int markManualReview(UUID paymentId, SettlementReviewReason reviewReason, Instant now);
    long countByStatus(PaymentStatus status);
    long countPendingCreatedBefore(Instant before);
    List<UUID> findPendingIdsCreatedBefore(Instant before, int limit);
}

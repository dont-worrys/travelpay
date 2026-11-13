package com.travelpay.payment.domain.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findById(UUID refundId);
    Optional<Refund> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    List<Refund> findAllByPaymentId(UUID paymentId);
    List<Refund> findAllByPaymentIdIn(Collection<UUID> paymentIds);
    List<Refund> findRecoveryTargets(Instant now, int limit);
    int claimForRecovery(UUID refundId, Instant seenUpdatedAt, Instant now);
    int recordUnknownAttempt(UUID refundId, int expectedAttempts, RefundStatus status,
                             SettlementReviewReason reviewReason, Instant now);
    int markManualReview(UUID refundId, SettlementReviewReason reviewReason, Instant now);
    Page<RefundManualReview> findManualReviews(SettlementReviewReason reviewReason, Pageable pageable);
    // 자동 처리 대상: MANUAL_REVIEW로 바뀐 지(updated_at) 기준 시각이 지난 환불을 (생성 시각, ID) 커서 다음부터 최대 limit건
    List<Refund> findManualReviewsAfter(Instant updatedBefore, Instant afterCreatedAt, UUID afterId, int limit);
    int resolveCompleted(UUID refundId, UUID ledgerId, Instant now);
    int resolveDeclined(UUID refundId, String declineReason, Instant now);
    int resolveRetry(UUID refundId, Instant now);
    long countByStatus(RefundStatus status);
    long countRequestedCreatedBefore(Instant before);
    List<UUID> findRequestedIdsCreatedBefore(Instant before, int limit);
}

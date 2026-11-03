package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundRepository;
import com.travelpay.payment.domain.payment.RefundStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RefundRepositoryImpl implements RefundRepository {

    private final RefundJpaRepository refundJpaRepository;

    @Override
    public Refund save(Refund refund) {
        return refundJpaRepository.save(refund);
    }

    @Override
    public Optional<Refund> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey) {
        return refundJpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    @Override
    public List<Refund> findAllByPaymentId(UUID paymentId) {
        return refundJpaRepository.findAllByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    @Override
    public List<Refund> findAllByPaymentIdIn(Collection<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return refundJpaRepository.findAllByPaymentIdIn(paymentIds);
    }

    @Override
    public List<Refund> findRecoveryTargets(Instant now, int limit) {
        return refundJpaRepository.findRecoveryTargets(now, limit);
    }

    @Override
    public int claimForRecovery(UUID refundId, Instant seenUpdatedAt, Instant now) {
        return refundJpaRepository.claimForRecovery(refundId, seenUpdatedAt, now);
    }

    @Override
    public int recordUnknownAttempt(UUID refundId, int expectedAttempts, RefundStatus status,
                                    SettlementReviewReason reviewReason, Instant now) {
        return refundJpaRepository.recordUnknownAttempt(refundId, expectedAttempts, status, reviewReason, now);
    }

    @Override
    public int markManualReview(UUID refundId, SettlementReviewReason reviewReason, Instant now) {
        return refundJpaRepository.markManualReview(refundId, reviewReason, now);
    }

    @Override
    public long countByStatus(RefundStatus status) {
        return refundJpaRepository.countByStatus(status);
    }

    @Override
    public long countRequestedCreatedBefore(Instant before) {
        return refundJpaRepository.countByStatusAndCreatedAtBefore(RefundStatus.REQUESTED, before);
    }

    @Override
    public List<UUID> findRequestedIdsCreatedBefore(Instant before, int limit) {
        return refundJpaRepository.findIdsByStatusAndCreatedAtBefore(
                RefundStatus.REQUESTED, before, PageRequest.of(0, limit));
    }
}

package com.travelpay.payment.infra.persistence.merchant;

import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SettlementRepositoryImpl implements SettlementRepository {

    private final SettlementJpaRepository settlementJpaRepository;

    @Override
    public Settlement save(Settlement settlement) {
        return settlementJpaRepository.save(settlement);
    }

    @Override
    public Optional<Settlement> findById(UUID settlementId) {
        return settlementJpaRepository.findById(settlementId);
    }

    @Override
    public Optional<Settlement> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey) {
        return settlementJpaRepository.findByRequestedByAndIdempotencyKey(requestedBy, idempotencyKey);
    }

    @Override
    public Page<Settlement> findPageByMerchantId(UUID merchantId, SettlementStatus status, Pageable pageable) {
        if (status == null) {
            return settlementJpaRepository.findByMerchantId(merchantId, pageable);
        }
        return settlementJpaRepository.findByMerchantIdAndStatus(merchantId, status, pageable);
    }

    @Override
    public Page<Settlement> findPageByOwnerUserId(UUID ownerUserId, SettlementStatus status, Pageable pageable) {
        if (status == null) {
            return settlementJpaRepository.findByOwnerUserId(ownerUserId, pageable);
        }
        return settlementJpaRepository.findByOwnerUserIdAndStatus(ownerUserId, status, pageable);
    }

    @Override
    public List<Settlement> findRecoveryTargets(Instant now, int limit) {
        return settlementJpaRepository.findRecoveryTargets(now, limit);
    }

    @Override
    public List<Settlement> findManualReviewsAfter(Instant updatedBefore, Instant afterCreatedAt, UUID afterId,
                                                   int limit) {
        return settlementJpaRepository.findManualReviewsAfter(updatedBefore, afterCreatedAt, afterId, limit);
    }

    @Override
    public int claimForRecovery(UUID settlementId, Instant seenUpdatedAt, Instant now) {
        return settlementJpaRepository.claimForRecovery(settlementId, seenUpdatedAt, now);
    }

    @Override
    public int recordUnknownAttempt(UUID settlementId, int expectedAttempts, SettlementStatus status,
                                    SettlementReviewReason reviewReason, Instant now) {
        return settlementJpaRepository.recordUnknownAttempt(settlementId, expectedAttempts, status, reviewReason, now);
    }

    @Override
    public int markManualReview(UUID settlementId, SettlementReviewReason reviewReason, Instant now) {
        return settlementJpaRepository.markManualReview(settlementId, reviewReason, now);
    }

    @Override
    public Page<Settlement> findManualReviews(SettlementReviewReason reviewReason, Pageable pageable) {
        if (reviewReason == null) {
            return settlementJpaRepository.findByStatus(SettlementStatus.MANUAL_REVIEW, pageable);
        }
        return settlementJpaRepository.findByStatusAndReviewReason(SettlementStatus.MANUAL_REVIEW, reviewReason,
                pageable);
    }

    @Override
    public long countManualReviews() {
        return settlementJpaRepository.countByStatus(SettlementStatus.MANUAL_REVIEW);
    }

    @Override
    public long countRequestedCreatedBefore(Instant before) {
        return settlementJpaRepository.countByStatusAndCreatedAtBefore(SettlementStatus.REQUESTED, before);
    }

    @Override
    public List<UUID> findRequestedIdsCreatedBefore(Instant before, int limit) {
        return settlementJpaRepository.findIdsByStatusAndCreatedAtBefore(
                SettlementStatus.REQUESTED, before, PageRequest.of(0, limit));
    }
}

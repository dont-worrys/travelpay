package com.travelpay.payment.domain.merchant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository {
    Settlement save(Settlement settlement);
    Optional<Settlement> findById(UUID settlementId);
    Optional<Settlement> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey);
    Page<Settlement> findPageByMerchantId(UUID merchantId, Pageable pageable);
    List<Settlement> findRecoveryTargets(Instant now, int limit);
    int claimForRecovery(UUID settlementId, Instant seenUpdatedAt, Instant now);
    int recordUnknownAttempt(UUID settlementId, int expectedAttempts, Instant now);
    long countRequestedCreatedBefore(Instant before);
    List<UUID> findRequestedIdsCreatedBefore(Instant before, int limit);
}

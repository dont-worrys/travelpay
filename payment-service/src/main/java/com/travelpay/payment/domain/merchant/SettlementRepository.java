package com.travelpay.payment.domain.merchant;

import com.travelpay.payment.domain.payment.SettlementReviewReason;
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
    // 결과 모름 횟수를 1 올리며 상태를 정함(한도면 MANUAL_REVIEW). REQUESTED이고 횟수가 그대로일 때만 바뀜
    int recordUnknownAttempt(UUID settlementId, int expectedAttempts, SettlementStatus status,
                             SettlementReviewReason reviewReason, Instant now);
    // REQUESTED일 때만 MANUAL_REVIEW로
    int markManualReview(UUID settlementId, SettlementReviewReason reviewReason, Instant now);
    // MANUAL_REVIEW 목록. reviewReason이 null이면 거르지 않음
    Page<Settlement> findManualReviews(SettlementReviewReason reviewReason, Pageable pageable);
    // 자동 처리 대상: MANUAL_REVIEW로 바뀐 지(updated_at) 기준 시각이 지난 정산을 (생성 시각, ID) 커서 다음부터 최대 limit건
    List<Settlement> findManualReviewsAfter(Instant updatedBefore, Instant afterCreatedAt, UUID afterId, int limit);
    long countManualReviews();
    long countRequestedCreatedBefore(Instant before);
    List<UUID> findRequestedIdsCreatedBefore(Instant before, int limit);
}

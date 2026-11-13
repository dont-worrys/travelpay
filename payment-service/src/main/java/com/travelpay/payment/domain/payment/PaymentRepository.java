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
    // 행을 잠그고 읽음 (환불 시작과 정산이 같은 결제를 동시에 다루지 않도록). 트랜잭션 안에서만 호출
    Optional<Payment> findByIdForUpdate(UUID paymentId);
    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    Page<Payment> findPageByUserId(UUID userId, PaymentStatus status, Pageable pageable);
    Page<Payment> findPageByMerchantId(UUID merchantId, PaymentStatus status, Pageable pageable);
    Page<Payment> findManualReviews(SettlementReviewReason reviewReason, Pageable pageable);
    // 자동 처리 대상: MANUAL_REVIEW로 바뀐 지(updated_at) 기준 시각이 지난 결제를 (생성 시각, ID) 커서 다음부터 최대 limit건
    List<Payment> findManualReviewsAfter(Instant updatedBefore, Instant afterCreatedAt, UUID afterId, int limit);
    // 기준 시각 전에 신청되어 아직 처리되지 않은 환불 신청
    Page<Payment> findRefundRequestsUpdatedBefore(Instant before, Pageable pageable);
    long countRefundRequestsUpdatedBefore(Instant before);
    // 정산 대상: 가맹점의 APPROVED 결제 중 처음 승인된 시각이 기준 전이고 아직 정산되지 않은 결제
    // 결제 생성 순으로 최대 limit건. 대상 결제 행을 잠그므로 트랜잭션 안에서만 호출
    List<Payment> findSettlementTargets(UUID merchantId, Instant approvedBefore, int limit);
    // 정산 미리보기: 가맹점의 미정산 APPROVED 결제 전부와 승인 시각(정산 대상과 같은 결제 생성 순). 잠그지 않음
    List<SettlementCandidate> findSettlementCandidates(UUID merchantId);
    // 복구 대상 일괄 선점: 대기 시간이 지난 건을 최대 limit건 골라 수정 시각을 claimedAt으로 바꿔 반환.
    // 다른 서버가 잠근 행은 건너뛰고, 커밋 뒤엔 대기 시간이 다시 시작돼 서버마다 서로 다른 건을 가져감
    List<Payment> claimRecoveryTargets(Instant now, Instant claimedAt, int limit);
    int claimForRecovery(UUID paymentId, Instant seenUpdatedAt, Instant now);
    int recordUnknownAttempt(UUID paymentId, int expectedAttempts, PaymentStatus status,
                             SettlementReviewReason reviewReason, Instant now);
    int markManualReview(UUID paymentId, SettlementReviewReason reviewReason, Instant now);
    long countByStatus(PaymentStatus status);
    long countPendingCreatedBefore(Instant before);
    List<UUID> findPendingIdsCreatedBefore(Instant before, int limit);
}

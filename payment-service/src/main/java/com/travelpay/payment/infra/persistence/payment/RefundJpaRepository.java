package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    List<Refund> findAllByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
    List<Refund> findAllByPaymentIdIn(Collection<UUID> paymentIds);

    @Query(nativeQuery = true, value = """
            select * from refunds
            where status = 'REQUESTED'
              and updated_at < cast(:now as timestamptz) - interval '30 seconds'
              and updated_at < cast(:now as timestamptz)
                  - interval '30 seconds' * power(2, greatest(attempt_count - 1, 0))
            order by updated_at, id
            limit :limit
            """)
    List<Refund> findRecoveryTargets(@Param("now") Instant now, @Param("limit") int limit);

    // 복구 선점: 조회 이후 아무도 바꾸지 않았을 때만 수정 시각 갱신
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Refund r
            set r.updatedAt = :now
            where r.id = :id
              and r.status = com.travelpay.payment.domain.payment.RefundStatus.REQUESTED
              and r.updatedAt = :seenUpdatedAt
            """)
    int claimForRecovery(@Param("id") UUID id,
                         @Param("seenUpdatedAt") Instant seenUpdatedAt,
                         @Param("now") Instant now);

    // 결과 모름 기록. 상태·시도 횟수가 기대값일 때만 반영
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Refund r
            set r.attemptCount = :expectedAttempts + 1, r.status = :status, r.reviewReason = :reviewReason,
                r.updatedAt = :now
            where r.id = :id
              and r.status = com.travelpay.payment.domain.payment.RefundStatus.REQUESTED
              and r.attemptCount = :expectedAttempts
            """)
    int recordUnknownAttempt(@Param("id") UUID id,
                             @Param("expectedAttempts") int expectedAttempts,
                             @Param("status") RefundStatus status,
                             @Param("reviewReason") SettlementReviewReason reviewReason,
                             @Param("now") Instant now);

    // 자동 처리 중단. 그 사이 결과가 반영된 환불은 바꾸지 않음
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Refund r
            set r.status = com.travelpay.payment.domain.payment.RefundStatus.MANUAL_REVIEW,
                r.reviewReason = :reviewReason, r.updatedAt = :now
            where r.id = :id
              and r.status = com.travelpay.payment.domain.payment.RefundStatus.REQUESTED
            """)
    int markManualReview(@Param("id") UUID id,
                         @Param("reviewReason") SettlementReviewReason reviewReason,
                         @Param("now") Instant now);

    long countByStatus(RefundStatus status);

    long countByStatusAndCreatedAtBefore(RefundStatus status, Instant before);

    @Query("select r.id from Refund r where r.status = :status and r.createdAt < :before order by r.createdAt")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") RefundStatus status,
                                                 @Param("before") Instant before,
                                                 Pageable pageable);
}

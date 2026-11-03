package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    Page<Payment> findByUserId(UUID userId, Pageable pageable);
    Page<Payment> findByUserIdAndStatus(UUID userId, PaymentStatus status, Pageable pageable);
    Page<Payment> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<Payment> findByMerchantIdAndStatus(UUID merchantId, PaymentStatus status, Pageable pageable);

    @Query(nativeQuery = true, value = """
            select * from payments
            where status = 'PENDING'
              and updated_at < cast(:now as timestamptz) - interval '30 seconds'
              and updated_at < cast(:now as timestamptz)
                  - interval '30 seconds' * power(2, greatest(attempt_count - 1, 0))
            order by updated_at, id
            limit :limit
            """)
    List<Payment> findRecoveryTargets(@Param("now") Instant now, @Param("limit") int limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Payment p
            set p.updatedAt = :now
            where p.id = :id
              and p.status = com.travelpay.payment.domain.payment.PaymentStatus.PENDING
              and p.updatedAt = :seenUpdatedAt
            """)
    int claimForRecovery(@Param("id") UUID id,
                         @Param("seenUpdatedAt") Instant seenUpdatedAt,
                         @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Payment p
            set p.attemptCount = :expectedAttempts + 1, p.status = :status, p.reviewReason = :reviewReason,
                p.updatedAt = :now, p.version = p.version + 1
            where p.id = :id
              and p.status = com.travelpay.payment.domain.payment.PaymentStatus.PENDING
              and p.attemptCount = :expectedAttempts
            """)
    int recordUnknownAttempt(@Param("id") UUID id,
                             @Param("expectedAttempts") int expectedAttempts,
                             @Param("status") PaymentStatus status,
                             @Param("reviewReason") SettlementReviewReason reviewReason,
                             @Param("now") Instant now);

    // 자동 처리 중단. 그 사이 결과가 반영된 결제는 바꾸지 않음
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Payment p
            set p.status = com.travelpay.payment.domain.payment.PaymentStatus.MANUAL_REVIEW,
                p.reviewReason = :reviewReason, p.updatedAt = :now, p.version = p.version + 1
            where p.id = :id
              and p.status = com.travelpay.payment.domain.payment.PaymentStatus.PENDING
            """)
    int markManualReview(@Param("id") UUID id,
                         @Param("reviewReason") SettlementReviewReason reviewReason,
                         @Param("now") Instant now);

    long countByStatus(PaymentStatus status);

    long countByStatusAndCreatedAtBefore(PaymentStatus status, Instant before);

    @Query("select p.id from Payment p where p.status = :status and p.createdAt < :before order by p.createdAt")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") PaymentStatus status,
                                                 @Param("before") Instant before,
                                                 Pageable pageable);
}

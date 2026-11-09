package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.SettlementCandidate;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    Page<Payment> findByUserId(UUID userId, Pageable pageable);
    Page<Payment> findByUserIdAndStatus(UUID userId, PaymentStatus status, Pageable pageable);
    Page<Payment> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<Payment> findByMerchantIdAndStatus(UUID merchantId, PaymentStatus status, Pageable pageable);
    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);
    Page<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant before, Pageable pageable);
    long countByStatusAndUpdatedAtBefore(PaymentStatus status, Instant before);
    Page<Payment> findByStatusAndReviewReason(PaymentStatus status, SettlementReviewReason reviewReason,
                                              Pageable pageable);

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

    // 정산 대상. 승인 시각은 환불 기간 계산(findApprovedAt)과 같은 기준: PENDING·MANUAL_REVIEW → APPROVED 이력.
    // 부분 환불·환불 거절·신청 취소·거절 뒤의 → APPROVED는 승인 시각이 아닙니다.
    // 대상 결제 행을 잠가 정산 트랜잭션 동안 환불이 시작되지 않게 합니다. 환불이 먼저 잠갔다면 커밋을 기다린 뒤
    // 바뀐 상태(REFUND_PENDING)로 조건을 다시 확인해 그 결제는 빠집니다.
    @Query(nativeQuery = true, value = """
            select p.* from payments p
            where p.merchant_id = :merchantId
              and p.status = 'APPROVED'
              and not exists (select 1 from settlement_items i where i.payment_id = p.id)
              and exists (select 1 from payment_histories h
                          where h.payment_id = p.id
                            and h.current_status = 'APPROVED'
                            and h.previous_status in ('PENDING', 'MANUAL_REVIEW')
                            and h.created_at < cast(:approvedBefore as timestamptz))
            order by p.created_at, p.id
            limit :limit
            for update of p
            """)
    List<Payment> findSettlementTargets(@Param("merchantId") UUID merchantId,
                                        @Param("approvedBefore") Instant approvedBefore,
                                        @Param("limit") int limit);

    // 정산 미리보기. findSettlementTargets와 같은 조건·순서(결제 생성 순)지만 승인 시각·건수 제한 없이 모두 읽고 잠그지 않습니다.
    @Query("""
            select new com.travelpay.payment.domain.payment.SettlementCandidate(p.id, p.amount, h.createdAt)
            from Payment p, PaymentHistory h
            where p.merchantId = :merchantId
              and p.status = com.travelpay.payment.domain.payment.PaymentStatus.APPROVED
              and h.paymentId = p.id
              and h.currentStatus = com.travelpay.payment.domain.payment.PaymentStatus.APPROVED
              and h.previousStatus in (com.travelpay.payment.domain.payment.PaymentStatus.PENDING,
                                       com.travelpay.payment.domain.payment.PaymentStatus.MANUAL_REVIEW)
              and not exists (select 1 from SettlementItem i where i.paymentId = p.id)
            order by p.createdAt, p.id
            """)
    List<SettlementCandidate> findSettlementCandidates(@Param("merchantId") UUID merchantId);

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

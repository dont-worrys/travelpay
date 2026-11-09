package com.travelpay.payment.infra.persistence.merchant;

import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementStatus;
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

public interface SettlementJpaRepository extends JpaRepository<Settlement, UUID> {
    Optional<Settlement> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey);

    Page<Settlement> findByMerchantId(UUID merchantId, Pageable pageable);

    @Query(nativeQuery = true, value = """
            select * from settlements
            where status = 'REQUESTED'
              and updated_at < cast(:now as timestamptz) - interval '30 seconds'
              and updated_at < cast(:now as timestamptz)
                  - interval '30 seconds' * power(2, greatest(attempt_count - 1, 0))
            order by updated_at, id
            limit :limit
            """)
    List<Settlement> findRecoveryTargets(@Param("now") Instant now, @Param("limit") int limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Settlement s
            set s.updatedAt = :now
            where s.id = :id
              and s.status = com.travelpay.payment.domain.merchant.SettlementStatus.REQUESTED
              and s.updatedAt = :seenUpdatedAt
            """)
    int claimForRecovery(@Param("id") UUID id,
                         @Param("seenUpdatedAt") Instant seenUpdatedAt,
                         @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Settlement s
            set s.attemptCount = :expectedAttempts + 1, s.updatedAt = :now, s.version = s.version + 1
            where s.id = :id
              and s.status = com.travelpay.payment.domain.merchant.SettlementStatus.REQUESTED
              and s.attemptCount = :expectedAttempts
            """)
    int recordUnknownAttempt(@Param("id") UUID id,
                             @Param("expectedAttempts") int expectedAttempts,
                             @Param("now") Instant now);

    long countByStatusAndCreatedAtBefore(SettlementStatus status, Instant before);

    @Query("select s.id from Settlement s where s.status = :status and s.createdAt < :before order by s.createdAt")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") SettlementStatus status,
                                                 @Param("before") Instant before,
                                                 Pageable pageable);
}

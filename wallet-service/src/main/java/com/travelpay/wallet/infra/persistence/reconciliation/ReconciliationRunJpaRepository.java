package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunJpaRepository extends JpaRepository<ReconciliationRun, UUID> {

    Optional<ReconciliationRun> findFirstByRunTypeAndStatusInOrderByPeriodToDescStartedAtDesc(
            ReconciliationRunType runType, Collection<ReconciliationRunStatus> statuses);

    boolean existsByStatus(ReconciliationRunStatus status);

    Page<ReconciliationRun> findByStatus(ReconciliationRunStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReconciliationRun r
               SET r.status = :status, r.targetCount = :targetCount, r.matchedCount = :matchedCount,
                   r.mismatchCount = :mismatchCount, r.unverifiableCount = :unverifiableCount, r.finishedAt = :now
             WHERE r.id = :runId AND r.status = com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus.RUNNING
            """)
    int finishIfRunning(@Param("runId") UUID runId, @Param("status") ReconciliationRunStatus status,
                        @Param("targetCount") int targetCount, @Param("matchedCount") int matchedCount,
                        @Param("mismatchCount") int mismatchCount, @Param("unverifiableCount") int unverifiableCount,
                        @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReconciliationRun r
               SET r.status = com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus.FAILED,
                   r.failureReason = :reason, r.finishedAt = :now
             WHERE r.id = :runId AND r.status = com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus.RUNNING
            """)
    int failIfRunning(@Param("runId") UUID runId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReconciliationRun r
               SET r.status = com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus.FAILED,
                   r.failureReason = :reason, r.finishedAt = :now
             WHERE r.status = com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus.RUNNING
               AND r.startedAt < :threshold
            """)
    int failStaleRunning(@Param("threshold") Instant threshold, @Param("reason") String reason,
                         @Param("now") Instant now);
}

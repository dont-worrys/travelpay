package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.MismatchType;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ResolutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ReconciliationResultJpaRepository extends JpaRepository<ReconciliationResult, UUID> {

    Page<ReconciliationResult> findByRunId(UUID runId, Pageable pageable);

    Page<ReconciliationResult> findByRunIdAndMismatchType(UUID runId, MismatchType mismatchType, Pageable pageable);

    /** 같은 충전의 결과이고 아직 처리 전(from)일 때만 처리 상태(to)와 보정 ID를 바꾼다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ReconciliationResult r
               set r.resolutionStatus = :to,
                   r.resolutionId = :resolutionId
             where r.id = :id
               and r.chargeId = :chargeId
               and r.resolutionStatus = :from
            """)
    int changeResolution(@Param("id") UUID id,
                         @Param("chargeId") UUID chargeId,
                         @Param("from") ResolutionStatus from,
                         @Param("to") ResolutionStatus to,
                         @Param("resolutionId") UUID resolutionId);
}

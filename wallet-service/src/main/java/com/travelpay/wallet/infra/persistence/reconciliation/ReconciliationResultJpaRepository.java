package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.MismatchType;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationResultJpaRepository extends JpaRepository<ReconciliationResult, UUID> {

    Page<ReconciliationResult> findByRunId(UUID runId, Pageable pageable);

    Page<ReconciliationResult> findByRunIdAndMismatchType(UUID runId, MismatchType mismatchType, Pageable pageable);
}

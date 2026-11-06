package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.MismatchType;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResultRepository;
import com.travelpay.wallet.domain.reconciliation.ResolutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReconciliationResultRepositoryImpl implements ReconciliationResultRepository {

    private final ReconciliationResultJpaRepository jpaRepository;

    @Override
    public void saveAll(List<ReconciliationResult> results) {
        jpaRepository.saveAll(results);
    }

    @Override
    public Page<ReconciliationResult> findByRun(UUID runId, MismatchType mismatchType, Pageable pageable) {
        return mismatchType == null
                ? jpaRepository.findByRunId(runId, pageable)
                : jpaRepository.findByRunIdAndMismatchType(runId, mismatchType, pageable);
    }

    @Override
    public Optional<ReconciliationResult> findById(UUID resultId) {
        return jpaRepository.findById(resultId);
    }

    @Override
    public int markResolved(UUID resultId, UUID chargeId, UUID resolutionId) {
        return jpaRepository.changeResolution(resultId, chargeId, ResolutionStatus.PENDING,
                ResolutionStatus.RESOLVED, resolutionId);
    }
}

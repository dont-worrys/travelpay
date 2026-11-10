package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.ReconciliationAlreadyRunningException;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunType;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReconciliationRunRepositoryImpl implements ReconciliationRunRepository {

    private static final String RUNNING_INDEX = "ux_reconciliation_runs_running";
    private static final List<ReconciliationRunStatus> CLOSED =
            List.of(ReconciliationRunStatus.COMPLETED, ReconciliationRunStatus.PARTIAL);

    private final ReconciliationRunJpaRepository jpaRepository;

    /** flush로 RUNNING 부분 UNIQUE 위반을 이 자리에서 드러낸다. 다른 무결성 위반은 그대로 던진다 */
    @Override
    public ReconciliationRun saveNew(ReconciliationRun run) {
        try {
            return jpaRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            if (isRunningViolation(e)) {
                throw new ReconciliationAlreadyRunningException(e);
            }
            throw e;
        }
    }

    @Override
    public Optional<ReconciliationRun> findById(UUID runId) {
        return jpaRepository.findById(runId);
    }

    @Override
    public Optional<ReconciliationRun> findLatestClosedAuto() {
        return jpaRepository.findFirstByRunTypeAndStatusInOrderByPeriodToDescStartedAtDesc(
                ReconciliationRunType.AUTO, CLOSED);
    }

    @Override
    public boolean existsRunning() {
        return jpaRepository.existsByStatus(ReconciliationRunStatus.RUNNING);
    }

    @Override
    public Page<ReconciliationRun> findAll(ReconciliationRunStatus status, Pageable pageable) {
        return status == null ? jpaRepository.findAll(pageable) : jpaRepository.findByStatus(status, pageable);
    }

    @Override
    public int finishIfRunning(UUID runId, ReconciliationRunStatus status, int targetCount, int matchedCount,
                               int mismatchCount, int unverifiableCount, Instant now) {
        return jpaRepository.finishIfRunning(runId, status, targetCount, matchedCount, mismatchCount,
                unverifiableCount, now);
    }

    @Override
    public int failIfRunning(UUID runId, String reason, Instant now) {
        return jpaRepository.failIfRunning(runId, ReconciliationRun.failureReasonOf(reason), now);
    }

    @Override
    public int failStaleRunning(Instant threshold, String reason, Instant now) {
        return jpaRepository.failStaleRunning(threshold, ReconciliationRun.failureReasonOf(reason), now);
    }

    private static boolean isRunningViolation(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                return RUNNING_INDEX.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}

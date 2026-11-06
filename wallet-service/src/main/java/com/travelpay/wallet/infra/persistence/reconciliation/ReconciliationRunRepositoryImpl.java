package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.ReconciliationAlreadyRunningException;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReconciliationRunRepositoryImpl implements ReconciliationRunRepository {

    private static final String RUNNING_INDEX = "ux_reconciliation_runs_running";

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

    private static boolean isRunningViolation(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                return RUNNING_INDEX.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}

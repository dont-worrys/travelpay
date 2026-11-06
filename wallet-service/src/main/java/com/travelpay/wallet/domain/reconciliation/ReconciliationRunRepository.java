package com.travelpay.wallet.domain.reconciliation;

import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepository {

    /** 바로 flush한다. RUNNING 실행이 이미 있으면 ReconciliationAlreadyRunningException */
    ReconciliationRun saveNew(ReconciliationRun run);

    Optional<ReconciliationRun> findById(UUID runId);
}

package com.travelpay.wallet.domain.reconciliation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepository {

    /** 바로 flush한다. RUNNING 실행이 이미 있으면 ReconciliationAlreadyRunningException */
    ReconciliationRun saveNew(ReconciliationRun run);

    Optional<ReconciliationRun> findById(UUID runId);

    /** AUTO 실행 중 COMPLETED·PARTIAL에서 period_to가 가장 늦은 실행 (FAILED·RUNNING·MANUAL 제외) */
    Optional<ReconciliationRun> findLatestClosedAuto();

    boolean existsRunning();

    /** status가 null이면 전체 */
    Page<ReconciliationRun> findAll(ReconciliationRunStatus status, Pageable pageable);

    /** RUNNING일 때만 COMPLETED·PARTIAL로 끝낸다. 바뀐 행 수(0 또는 1) */
    int finishIfRunning(UUID runId, ReconciliationRunStatus status, int targetCount, int matchedCount,
                        int mismatchCount, int unverifiableCount, Instant now);

    /** RUNNING일 때만 FAILED로 끝낸다. 바뀐 행 수(0 또는 1) */
    int failIfRunning(UUID runId, String reason, Instant now);

    /** started_at이 threshold보다 앞선 RUNNING을 FAILED로 끝낸다. 바뀐 행 수 */
    int failStaleRunning(Instant threshold, String reason, Instant now);
}

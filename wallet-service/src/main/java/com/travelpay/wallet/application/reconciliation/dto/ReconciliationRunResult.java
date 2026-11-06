package com.travelpay.wallet.application.reconciliation.dto;

import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;

import java.util.UUID;

/** 대사 실행 1회의 요약 */
public record ReconciliationRunResult(UUID runId, ReconciliationRunStatus status, int targetCount,
                                      int matchedCount, int mismatchCount, int unverifiableCount,
                                      String failureReason) {

    public static ReconciliationRunResult from(ReconciliationRun run) {
        return new ReconciliationRunResult(run.getId(), run.getStatus(), run.getTargetCount(),
                run.getMatchedCount(), run.getMismatchCount(), run.getUnverifiableCount(), run.getFailureReason());
    }
}

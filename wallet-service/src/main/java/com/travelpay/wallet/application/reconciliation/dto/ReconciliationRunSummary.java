package com.travelpay.wallet.application.reconciliation.dto;

import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunType;

import java.time.Instant;
import java.util.UUID;

/** 관리자 조회용 실행 요약 */
public record ReconciliationRunSummary(UUID runId, ReconciliationRunType runType, ReconciliationRunStatus status,
                                       Instant periodFrom, Instant periodTo, Instant overlapFrom, Instant startedAt,
                                       Instant finishedAt, int targetCount, int matchedCount, int mismatchCount,
                                       int unverifiableCount, String failureReason) {

    public static ReconciliationRunSummary from(ReconciliationRun run) {
        return new ReconciliationRunSummary(run.getId(), run.getRunType(), run.getStatus(), run.getPeriodFrom(),
                run.getPeriodTo(), run.getOverlapFrom(), run.getStartedAt(), run.getFinishedAt(),
                run.getTargetCount(), run.getMatchedCount(), run.getMismatchCount(), run.getUnverifiableCount(),
                run.getFailureReason());
    }
}

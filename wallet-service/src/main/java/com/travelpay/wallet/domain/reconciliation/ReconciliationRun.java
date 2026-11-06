package com.travelpay.wallet.domain.reconciliation;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 충전 대사 실행 1회. RUNNING으로 시작해 COMPLETED·PARTIAL·FAILED 중 하나로 끝난다 */
@Getter
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    private static final int FAILURE_REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "period_from", nullable = false, updatable = false)
    private Instant periodFrom;

    @Column(name = "period_to", nullable = false, updatable = false)
    private Instant periodTo;

    /** 이전 기간과 겹쳐 다시 보는 시작 시각. 스케줄러 도입 전에는 null */
    @Column(name = "overlap_from", updatable = false)
    private Instant overlapFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReconciliationRunStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "unverifiable_count", nullable = false)
    private int unverifiableCount;

    @Column(name = "failure_reason", length = FAILURE_REASON_MAX)
    private String failureReason;

    protected ReconciliationRun() {
    }

    private ReconciliationRun(Instant periodFrom, Instant periodTo, Instant startedAt) {
        this.periodFrom = Objects.requireNonNull(periodFrom, "periodFrom");
        this.periodTo = Objects.requireNonNull(periodTo, "periodTo");
        if (!periodFrom.isBefore(periodTo)) {
            throw new IllegalArgumentException("대사 기간 시작이 종료보다 앞서야 합니다.");
        }
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.status = ReconciliationRunStatus.RUNNING;
    }

    public static ReconciliationRun start(Instant periodFrom, Instant periodTo, Instant now) {
        return new ReconciliationRun(periodFrom, periodTo, now);
    }

    /** 판정 완료. unverifiableCount가 0보다 크면 PARTIAL, 아니면 COMPLETED */
    public void finish(int targetCount, int matchedCount, int mismatchCount, int unverifiableCount, Instant now) {
        requireRunning();
        this.targetCount = targetCount;
        this.matchedCount = matchedCount;
        this.mismatchCount = mismatchCount;
        this.unverifiableCount = unverifiableCount;
        this.status = unverifiableCount > 0 ? ReconciliationRunStatus.PARTIAL : ReconciliationRunStatus.COMPLETED;
        this.finishedAt = Objects.requireNonNull(now, "now");
    }

    /** 결과 없이 실패로 끝냄 */
    public void fail(String reason, Instant now) {
        requireRunning();
        this.status = ReconciliationRunStatus.FAILED;
        this.failureReason = truncate(reason);
        this.finishedAt = Objects.requireNonNull(now, "now");
    }

    private void requireRunning() {
        if (status != ReconciliationRunStatus.RUNNING) {
            throw new IllegalStateException("RUNNING 실행만 끝낼 수 있습니다: " + status);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= FAILURE_REASON_MAX) {
            return value;
        }
        return value.substring(0, FAILURE_REASON_MAX);
    }
}

package com.travelpay.wallet.domain.reconciliation;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 대사 어긋남 1건. 충전 결과는 chargeId만, 고아 결과는 sourceType·sourceKey만 가진다.
 * snapshot은 판정 시점에 확인한 사실(JSON)이다.
 */
@Getter
@Entity
@Table(name = "reconciliation_results")
public class ReconciliationResult {

    private static final int DETAIL_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "charge_id", updatable = false)
    private UUID chargeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", updatable = false, length = 10)
    private SourceType sourceType;

    @Column(name = "source_key", updatable = false, length = 100)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "mismatch_type", nullable = false, updatable = false, length = 40)
    private MismatchType mismatchType;

    @Column(name = "detail", nullable = false, updatable = false, length = DETAIL_MAX)
    private String detail;

    /** JSON 문자열 (JSONB 컬럼) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, updatable = false)
    private String snapshot;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 20)
    private ResolutionStatus resolutionStatus;

    protected ReconciliationResult() {
    }

    private ReconciliationResult(UUID runId, UUID chargeId, SourceType sourceType, String sourceKey,
                                 MismatchType mismatchType, String detail, String snapshot, Instant detectedAt) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.chargeId = chargeId;
        this.sourceType = sourceType;
        this.sourceKey = sourceKey;
        this.mismatchType = Objects.requireNonNull(mismatchType, "mismatchType");
        this.detail = truncate(Objects.requireNonNull(detail, "detail"));
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
        this.resolutionStatus = ResolutionStatus.PENDING;
    }

    public static ReconciliationResult ofCharge(UUID runId, UUID chargeId, MismatchType mismatchType,
                                                String detail, String snapshot, Instant detectedAt) {
        return new ReconciliationResult(runId, Objects.requireNonNull(chargeId, "chargeId"), null, null,
                mismatchType, detail, snapshot, detectedAt);
    }

    public static ReconciliationResult ofOrphan(UUID runId, SourceType sourceType, String sourceKey,
                                                MismatchType mismatchType, String detail, String snapshot,
                                                Instant detectedAt) {
        return new ReconciliationResult(runId, null, Objects.requireNonNull(sourceType, "sourceType"),
                Objects.requireNonNull(sourceKey, "sourceKey"), mismatchType, detail, snapshot, detectedAt);
    }

    private static String truncate(String value) {
        return value.length() <= DETAIL_MAX ? value : value.substring(0, DETAIL_MAX);
    }
}

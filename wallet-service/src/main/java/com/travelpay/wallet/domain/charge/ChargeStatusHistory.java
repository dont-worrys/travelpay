package com.travelpay.wallet.domain.charge;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 상태 전이 이력. 조건부 UPDATE와 같은 트랜잭션에서 INSERT한다. */
@Getter
@Entity
@Table(name = "charge_status_history")
public class ChargeStatusHistory {

    /** reason_detail 길이. 보정 사유 입력 한도도 이 값이다 */
    public static final int REASON_DETAIL_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    /** 최초 접수(REQUESTED)면 null */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 30)
    private ChargeStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private ChargeStatus toStatus;

    @Column(name = "reason_code", updatable = false, length = 50)
    private String reasonCode;

    @Column(name = "reason_detail", updatable = false, length = 500)
    private String reasonDetail;

    @Column(name = "trace_id", updatable = false, length = 64)
    private String traceId;

    /** 운영자 보정으로 바뀐 줄만 가진다 */
    @Column(name = "resolution_id", updatable = false)
    private UUID resolutionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChargeStatusHistory() {
    }

    private ChargeStatusHistory(UUID chargeId, ChargeStatus fromStatus, ChargeStatus toStatus,
                                String reasonCode, String reasonDetail, String traceId, UUID resolutionId) {
        this.chargeId = Objects.requireNonNull(chargeId, "chargeId");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus");
        if ((fromStatus == null) != (toStatus == ChargeStatus.REQUESTED)) {
            throw new IllegalArgumentException(
                    "최초 접수(REQUESTED)만 이전 상태 없이 기록할 수 있습니다: " + fromStatus + " -> " + toStatus);
        }
        this.reasonCode = reasonCode;
        this.reasonDetail = truncate(reasonDetail);
        this.traceId = traceId;
        this.resolutionId = resolutionId;
        this.createdAt = Instant.now();
    }

    public static ChargeStatusHistory record(UUID chargeId, ChargeStatus fromStatus, ChargeStatus toStatus,
                                             String reasonCode, String reasonDetail, String traceId) {
        return new ChargeStatusHistory(chargeId, fromStatus, toStatus,
                reasonCode, reasonDetail, traceId, null);
    }

    /** 운영자 보정 이력. reasonDetail(사유)은 호출자가 길이를 검증해 넘긴다 */
    public static ChargeStatusHistory resolved(UUID chargeId, ChargeStatus fromStatus, ChargeStatus toStatus,
                                               String reasonCode, String reasonDetail, UUID resolutionId) {
        return new ChargeStatusHistory(chargeId, fromStatus, toStatus,
                reasonCode, reasonDetail, null, Objects.requireNonNull(resolutionId, "resolutionId"));
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= REASON_DETAIL_MAX) {
            return value;
        }
        return value.substring(0, REASON_DETAIL_MAX);
    }
}

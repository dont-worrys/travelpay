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

    private static final int REASON_DETAIL_MAX = 500;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChargeStatusHistory() {
    }

    private ChargeStatusHistory(UUID chargeId, ChargeStatus fromStatus, ChargeStatus toStatus,
                                String reasonCode, String reasonDetail, String traceId) {
        this.chargeId = Objects.requireNonNull(chargeId, "chargeId");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus");
        this.reasonCode = reasonCode;
        this.reasonDetail = truncate(reasonDetail);
        this.traceId = traceId;
        this.createdAt = Instant.now();
    }

    public static ChargeStatusHistory record(UUID chargeId, ChargeStatus fromStatus, ChargeStatus toStatus,
                                             String reasonCode, String reasonDetail, String traceId) {
        return new ChargeStatusHistory(chargeId, fromStatus, toStatus,
                reasonCode, reasonDetail, traceId);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= REASON_DETAIL_MAX) {
            return value;
        }
        return value.substring(0, REASON_DETAIL_MAX);
    }
}

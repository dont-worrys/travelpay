package com.travelpay.wallet.domain.resolution;

import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.charge.ChargeStatusHistory;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 성공한 충전 보정 1건. 충전 상태 변경·상태 이력·대사 결과 연결과 같은 트랜잭션에서 저장한다.
 * 같은 Idempotency-Key 재요청은 이 기록으로 처음 결과를 그대로 돌려준다 (재조회·재반영 없음)
 */
@Getter
@Entity
@Table(name = "charge_resolutions")
public class ChargeResolution {

    /** 사유는 상태 이력 reason_detail에 그대로 남기므로 그 길이를 넘지 않는다 (자르지 않음) */
    public static final int REASON_MAX = ChargeStatusHistory.REASON_DETAIL_MAX;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    @Column(name = "operator_id", nullable = false, updatable = false)
    private UUID operatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 30)
    private ResolutionAction action;

    @Column(name = "reason", nullable = false, updatable = false, length = REASON_MAX)
    private String reason;

    @Embedded
    @AttributeOverride(name = "lookup", column = @Column(name = "withdrawal_lookup", nullable = false, updatable = false))
    @AttributeOverride(name = "transactionId", column = @Column(name = "withdrawal_tx_id", updatable = false))
    @AttributeOverride(name = "status", column = @Column(name = "withdrawal_status", updatable = false))
    @AttributeOverride(name = "amount", column = @Column(name = "withdrawal_amount", updatable = false))
    private ResolutionEvidence withdrawal;

    @Embedded
    @AttributeOverride(name = "lookup", column = @Column(name = "refund_lookup", nullable = false, updatable = false))
    @AttributeOverride(name = "transactionId", column = @Column(name = "refund_tx_id", updatable = false))
    @AttributeOverride(name = "status", column = @Column(name = "refund_status", updatable = false))
    @AttributeOverride(name = "amount", column = @Column(name = "refund_amount", updatable = false))
    private ResolutionEvidence refund;

    @Embedded
    @AttributeOverride(name = "lookup", column = @Column(name = "ledger_lookup", nullable = false, updatable = false))
    @AttributeOverride(name = "transactionId", column = @Column(name = "ledger_tx_id", updatable = false))
    @AttributeOverride(name = "status", column = @Column(name = "ledger_status", updatable = false))
    @AttributeOverride(name = "amount", column = @Column(name = "ledger_amount", updatable = false))
    private ResolutionEvidence ledger;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, updatable = false, length = 30)
    private ChargeStatus resultStatus;

    @Column(name = "reconciliation_result_id", updatable = false)
    private UUID reconciliationResultId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChargeResolution() {
    }

    private ChargeResolution(UUID idempotencyKey, UUID chargeId, UUID operatorId, ResolutionAction action,
                             String reason, ResolutionEvidence withdrawal, ResolutionEvidence refund,
                             ResolutionEvidence ledger, UUID reconciliationResultId) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.chargeId = Objects.requireNonNull(chargeId, "chargeId");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
        this.action = Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > REASON_MAX) {
            throw new IllegalArgumentException("보정 사유는 1~" + REASON_MAX + "자여야 합니다.");
        }
        this.reason = reason;
        this.withdrawal = Objects.requireNonNull(withdrawal, "withdrawal");
        this.refund = Objects.requireNonNull(refund, "refund");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.resultStatus = action.targetStatus();
        this.reconciliationResultId = reconciliationResultId;
        // DB(timestamptz) 정밀도로 맞춰, 처음 응답과 멱등 재요청 응답(DB에서 읽은 값)이 같게 한다
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static ChargeResolution record(UUID idempotencyKey, UUID chargeId, UUID operatorId,
                                          ResolutionAction action, String reason, ResolutionEvidence withdrawal,
                                          ResolutionEvidence refund, ResolutionEvidence ledger,
                                          UUID reconciliationResultId) {
        return new ChargeResolution(idempotencyKey, chargeId, operatorId, action, reason, withdrawal, refund,
                ledger, reconciliationResultId);
    }

    /** 같은 Idempotency-Key 재요청이 처음 요청과 같은 내용인지 */
    public boolean isSameRequest(UUID chargeId, UUID operatorId, ResolutionAction action, String reason,
                                 UUID reconciliationResultId) {
        return this.chargeId.equals(chargeId)
                && this.operatorId.equals(operatorId)
                && this.action == action
                && this.reason.equals(reason)
                && Objects.equals(this.reconciliationResultId, reconciliationResultId);
    }
}

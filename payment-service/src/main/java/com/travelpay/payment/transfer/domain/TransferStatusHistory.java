package com.travelpay.payment.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 송금 상태 변경 이력입니다. MANUAL_REVIEW 확정(운영자 확정·스케줄러 자동 대사)만 기록합니다. */
@Entity
@Table(name = "transfer_status_history")
public class TransferStatusHistory {

    /** 상태를 바꾼 주체입니다. */
    public enum ActorType {
        /** 운영자 확정. actorId는 운영자 회원 ID입니다. */
        OPERATOR,
        /** MANUAL_REVIEW 자동 대사 스케줄러. actorId가 없습니다. */
        SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, nullable = false, updatable = false)
    private TransferStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 20, nullable = false, updatable = false)
    private TransferStatus toStatus;

    @Column(name = "reason", length = 255, nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 20, nullable = false, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "ledger_transaction_id", updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferStatusHistory() {
    }

    public static TransferStatusHistory byOperator(UUID transferId, TransferStatus fromStatus, TransferStatus toStatus,
            String reason, UUID operatorId, UUID ledgerTransactionId, Instant createdAt) {
        TransferStatusHistory history = new TransferStatusHistory();
        history.transferId = Objects.requireNonNull(transferId, "transferId");
        history.fromStatus = Objects.requireNonNull(fromStatus, "fromStatus");
        history.toStatus = Objects.requireNonNull(toStatus, "toStatus");
        history.reason = Objects.requireNonNull(reason, "reason");
        history.actorType = ActorType.OPERATOR;
        history.actorId = Objects.requireNonNull(operatorId, "operatorId");
        history.ledgerTransactionId = ledgerTransactionId;
        history.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return history;
    }

    /** MANUAL_REVIEW 자동 대사로 확정한 이력입니다. 근거 원장 거래 ID가 반드시 있습니다. */
    public static TransferStatusHistory bySystem(UUID transferId, TransferStatus fromStatus, TransferStatus toStatus,
            String reason, UUID ledgerTransactionId, Instant createdAt) {
        TransferStatusHistory history = new TransferStatusHistory();
        history.transferId = Objects.requireNonNull(transferId, "transferId");
        history.fromStatus = Objects.requireNonNull(fromStatus, "fromStatus");
        history.toStatus = Objects.requireNonNull(toStatus, "toStatus");
        history.reason = Objects.requireNonNull(reason, "reason");
        history.actorType = ActorType.SYSTEM;
        history.ledgerTransactionId = Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId");
        history.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return history;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public TransferStatus getFromStatus() {
        return fromStatus;
    }

    public TransferStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

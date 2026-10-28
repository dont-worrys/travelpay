package com.travelpay.ledger.domain.transaction;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 돈이 움직인 사건. INSERT만 하고 수정하지 않는다.
 * 되돌릴 때는 반대 방향의 새 거래를 만들고 originalTransactionId로 원거래를 가리킨다.
 * 멱등 키: UNIQUE(reference_type, reference_id)
 */
@Getter
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, updatable = false, length = 30)
    private ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private LedgerTransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason", updatable = false, length = 50)
    private RejectReason rejectReason;

    @Column(name = "original_transaction_id", updatable = false)
    private UUID originalTransactionId;

    @Column(name = "description", updatable = false, length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    private LedgerTransaction(ReferenceType referenceType, UUID referenceId, String requestHash,
                              UUID userId, LedgerTransactionStatus status, RejectReason rejectReason,
                              UUID originalTransactionId, String description) {
        this.referenceType = Objects.requireNonNull(referenceType, "referenceType");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.userId = userId;
        this.status = status;
        this.rejectReason = rejectReason;
        this.originalTransactionId = originalTransactionId;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public static LedgerTransaction posted(ReferenceType referenceType, UUID referenceId, String requestHash,
                                           UUID userId, UUID originalTransactionId, String description) {
        return new LedgerTransaction(referenceType, referenceId, requestHash, userId,
                LedgerTransactionStatus.POSTED, null, originalTransactionId, description);
    }

    /** 확정 거절만 기록한다. 일시적 실패는 기록하지 않고 롤백 + 5xx */
    public static LedgerTransaction rejected(ReferenceType referenceType, UUID referenceId, String requestHash,
                                             UUID userId, RejectReason rejectReason, String description) {
        return new LedgerTransaction(referenceType, referenceId, requestHash, userId,
                LedgerTransactionStatus.REJECTED, Objects.requireNonNull(rejectReason, "rejectReason"),
                null, description);
    }

    public boolean isPosted() {
        return status == LedgerTransactionStatus.POSTED;
    }

    public boolean hasSameRequest(String otherHash) {
        return requestHash.equals(otherHash);
    }
}

package com.travelpay.payment.transfer.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사용자 간 지갑 송금의 접수 기록입니다.
 * 회원·지갑은 다른 서비스 소유이므로 ID 값만 저장하고, 잔액 변경은 원장에 위임합니다.
 */
@Entity
@Table(name = "transfer")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Column(name = "receiver_id", nullable = false, updatable = false)
    private UUID receiverId;

    @Column(name = "source_wallet_id", nullable = false, updatable = false)
    private UUID sourceWalletId;

    @Column(name = "target_wallet_id", nullable = false, updatable = false)
    private UUID targetWalletId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TransferStatus status;

    /** 원장이 확정 거절한 사유이며 FAILED일 때만 있습니다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private TransferFailureReason failureReason;

    @Column(name = "memo", length = 100)
    private String memo;

    @Column(name = "idempotency_key", length = 100, nullable = false, updatable = false)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
    }

    private Transfer(
            UUID senderId,
            UUID receiverId,
            UUID sourceWalletId,
            UUID targetWalletId,
            long amount,
            String currency,
            String memo,
            String idempotencyKey,
            String requestHash,
            TransferStatus status
    ) {
        this.senderId = Objects.requireNonNull(senderId, "senderId");
        this.receiverId = Objects.requireNonNull(receiverId, "receiverId");
        this.sourceWalletId = Objects.requireNonNull(sourceWalletId, "sourceWalletId");
        this.targetWalletId = Objects.requireNonNull(targetWalletId, "targetWalletId");
        this.amount = amount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.memo = memo;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.status = status;
    }

    /** 검증을 마친 송금 요청을 접수 상태로 생성합니다. 원장 거래 등록 구현이 없을 때 사용합니다. */
    public static Transfer request(
            UUID senderId,
            UUID receiverId,
            UUID sourceWalletId,
            UUID targetWalletId,
            long amount,
            String currency,
            String memo,
            String idempotencyKey,
            String requestHash
    ) {
        return new Transfer(
                senderId,
                receiverId,
                sourceWalletId,
                targetWalletId,
                amount,
                currency,
                memo,
                idempotencyKey,
                requestHash,
                TransferStatus.REQUESTED
        );
    }

    /** 검증을 마친 송금 요청을 처리 중 상태로 생성합니다. 원장 거래 등록을 호출하기 전에 저장합니다. */
    public static Transfer process(
            UUID senderId,
            UUID receiverId,
            UUID sourceWalletId,
            UUID targetWalletId,
            long amount,
            String currency,
            String memo,
            String idempotencyKey,
            String requestHash
    ) {
        return new Transfer(
                senderId,
                receiverId,
                sourceWalletId,
                targetWalletId,
                amount,
                currency,
                memo,
                idempotencyKey,
                requestHash,
                TransferStatus.PROCESSING
        );
    }

    /** 송금인 또는 수취인인지 확인합니다. */
    public boolean isParticipant(UUID userId) {
        return senderId.equals(userId) || receiverId.equals(userId);
    }

    // TIMESTAMPTZ가 마이크로초까지 저장하므로, 저장 직후 응답 시각과 이후 조회 시각이 같도록 맞춥니다.
    @PrePersist
    void onCreate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getReceiverId() {
        return receiverId;
    }

    public UUID getSourceWalletId() {
        return sourceWalletId;
    }

    public UUID getTargetWalletId() {
        return targetWalletId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public TransferFailureReason getFailureReason() {
        return failureReason;
    }

    public String getMemo() {
        return memo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

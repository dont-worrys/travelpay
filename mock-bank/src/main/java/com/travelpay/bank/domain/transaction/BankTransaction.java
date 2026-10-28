package com.travelpay.bank.domain.transaction;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 출금·환불 기록. 같은 requestId가 다시 오면 새로 처리하지 않고 이 기록을 그대로 돌려준다.
 * 업무 거절도 FAILED로 남겨서 재조회에 같은 답을 준다.
 */
@Getter
@Entity
@Table(name = "bank_transactions")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "request_id", nullable = false, updatable = false, length = 100)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private BankTransactionType type;

    /** 계좌 없음으로 거절되면 null */
    @Column(name = "from_account_id", updatable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", updatable = false)
    private UUID toAccountId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private BankTransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", updatable = false, length = 50)
    private BankFailureCode failureCode;

    /** 환불만 사용 */
    @Column(name = "original_tx_id", updatable = false)
    private UUID originalTxId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at", updatable = false)
    private Instant completedAt;

    protected BankTransaction() {
    }

    private BankTransaction(
            String requestId,
            BankTransactionType type,
            UUID fromAccountId,
            UUID toAccountId,
            long amount,
            BankTransactionStatus status,
            BankFailureCode failureCode,
            UUID originalTxId
    ) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }

        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.type = type;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.status = status;
        this.failureCode = failureCode;
        this.originalTxId = originalTxId;

        Instant now = Instant.now();
        this.createdAt = now;
        this.completedAt = now; // 동기 처리라 생성과 동시에 완료
    }

    public static BankTransaction succeededDebit(
            String requestId,
            UUID fromAccountId,
            UUID toAccountId,
            long amount
    ) {
        return new BankTransaction(
                requestId,
                BankTransactionType.DEBIT,
                Objects.requireNonNull(fromAccountId, "fromAccountId"),
                Objects.requireNonNull(toAccountId, "toAccountId"),
                amount,
                BankTransactionStatus.SUCCEEDED,
                null,
                null
        );
    }

    public static BankTransaction failedDebit(
            String requestId,
            UUID fromAccountId,
            UUID toAccountId,
            long amount,
            BankFailureCode failureCode
    ) {
        return new BankTransaction(
                requestId,
                BankTransactionType.DEBIT,
                fromAccountId,
                toAccountId,
                amount,
                BankTransactionStatus.FAILED,
                Objects.requireNonNull(failureCode, "failureCode"),
                null
        );
    }

    /** 환불: 플랫폼 계좌(from) → 사용자 계좌(to) */
    public static BankTransaction succeededRefund(
            String requestId,
            UUID fromAccountId,
            UUID toAccountId,
            long amount,
            UUID originalTxId
    ) {
        return new BankTransaction(
                requestId,
                BankTransactionType.REFUND,
                Objects.requireNonNull(fromAccountId, "fromAccountId"),
                Objects.requireNonNull(toAccountId, "toAccountId"),
                amount,
                BankTransactionStatus.SUCCEEDED,
                null,
                Objects.requireNonNull(originalTxId, "originalTxId")
        );
    }

    /** 원거래가 없으면 originalTxId는 null */
    public static BankTransaction failedRefund(
            String requestId,
            UUID fromAccountId,
            UUID toAccountId,
            long amount,
            UUID originalTxId,
            BankFailureCode failureCode
    ) {
        return new BankTransaction(
                requestId,
                BankTransactionType.REFUND,
                fromAccountId,
                toAccountId,
                amount,
                BankTransactionStatus.FAILED,
                Objects.requireNonNull(failureCode, "failureCode"),
                originalTxId
        );
    }

    public boolean isSucceeded() {
        return status == BankTransactionStatus.SUCCEEDED;
    }

    /** 환불 가능한 원거래인지: 성공한 출금 */
    public boolean isRefundable() {
        return type == BankTransactionType.DEBIT && isSucceeded();
    }
}

package com.travelpay.wallet.domain.charge;

import com.travelpay.wallet.domain.Currency;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


/**
 * 충전 요청 (SAGA 상태 보관).
 *
 * 상태 전이는 엔티티 메서드가 아니라 리포지토리의 조건부 UPDATE로 한다.
 *   UPDATE charge_requests SET status = :to, updated_at = :now ...
 *   WHERE id = :id AND status = :from
 * JPQL 벌크 UPDATE는 @PreUpdate가 동작하지 않으므로 updated_at을 쿼리에서 직접 갱신해야 한다.
 * (복구 스케줄러가 updated_at으로 멈춘 건을 찾기 때문)
 */
@Getter
@Entity
@Table(name = "charge_requests")
public class ChargeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    /** bank_code, account_number_enc(암호문). 마스킹 값은 응답을 만들 때만 복호화해 만든다 */
    @Embedded
    private BankAccount bankAccount;

    @Column(name = "holder_name_snapshot", nullable = false, updatable = false, length = 100)
    private String holderNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ChargeStatus status;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "bank_request_id", nullable = false, updatable = false, length = 100)
    private String bankRequestId;

    @Column(name = "refund_request_id", nullable = false, updatable = false, length = 100)
    private String refundRequestId;

    @Column(name = "bank_tx_id")
    private UUID bankTxId;

    @Column(name = "refund_bank_tx_id")
    private UUID refundBankTxId;

    @Column(name = "ledger_ref")
    private UUID ledgerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 50)
    private ChargeFailureCode failureCode;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChargeRequest() {
    }

    private ChargeRequest(UUID userId, UUID walletId, long amount, Currency currency,
                          BankAccount bankAccount, String holderNameSnapshot, UUID idempotencyKey) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }
        this.userId = Objects.requireNonNull(userId, "userId");
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.amount = amount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.bankAccount = Objects.requireNonNull(bankAccount, "bankAccount");
        this.holderNameSnapshot = Objects.requireNonNull(holderNameSnapshot, "holderNameSnapshot");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.status = ChargeStatus.REQUESTED;
        // 은행 출금·환불 요청 ID는 접수 시 생성해 고정 (재시도해도 같은 ID로 보냄)
        this.bankRequestId = UUID.randomUUID().toString();
        this.refundRequestId = UUID.randomUUID().toString();
        this.attemptCount = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ChargeRequest create(UUID userId, UUID walletId, long amount, Currency currency,
                                       BankAccount bankAccount, String holderNameSnapshot,
                                       UUID idempotencyKey) {
        return new ChargeRequest(userId, walletId, amount, currency,
                bankAccount, holderNameSnapshot, idempotencyKey);
    }

    /** 같은 멱등 키 재요청이 처음 요청과 같은 내용인지. 계좌번호는 복호화해 비교한다 */
    public boolean isSameRequest(long amount, Currency currency, String bankCode, String accountNumber,
                                 AccountNumberCipher cipher) {
        return this.amount == amount
                && this.currency == currency
                && bankAccount.matches(bankCode, accountNumber, cipher);
    }

    public boolean isOwnedBy(UUID requesterId) {
        return userId.equals(requesterId);
    }
}

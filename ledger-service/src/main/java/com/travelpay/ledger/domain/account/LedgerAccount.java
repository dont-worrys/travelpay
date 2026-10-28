package com.travelpay.ledger.domain.account;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.transaction.Direction;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 원장 계정(통)과 현재 잔액.
 * 잔액 변경은 거래 등록 트랜잭션 안에서 계정 ID 순 FOR UPDATE로 잠근 뒤에만 한다.
 * CREDIT은 잔액 증가, DEBIT은 잔액 감소.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, updatable = false, length = 20)
    private OwnerType ownerType;

    /** USER 계정만 값이 있음 */
    @Column(name = "wallet_id", updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 30)
    private AccountType accountType;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerAccount() {
    }

    private LedgerAccount(OwnerType ownerType, UUID walletId,
                          Currency currency, AccountType accountType) {
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.walletId = walletId;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.accountType = Objects.requireNonNull(accountType, "accountType");
        this.balance = 0L;
        this.status = AccountStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static LedgerAccount forUserWallet(UUID walletId, Currency currency) {
        return new LedgerAccount(OwnerType.USER,
                Objects.requireNonNull(walletId, "walletId"), currency, AccountType.WALLET);
    }

    public static LedgerAccount forSystem(AccountType accountType, Currency currency) {
        return new LedgerAccount(OwnerType.SYSTEM, null, currency, accountType);
    }

    public boolean isUserAccount() {
        return ownerType == OwnerType.USER;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /** USER 계정에서 DEBIT 시 잔액이 모자라면 false (시스템 계정은 음수 허용이라 항상 true) */
    public boolean canApply(Direction direction, long amount) {
        if (!isUserAccount() || direction == Direction.CREDIT) {
            return true;
        }
        return balance >= amount;
    }

    /** 잔액을 반영하고 반영 후 잔액(balance_after)을 돌려준다 */
    public long apply(Direction direction, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }
        long next = (direction == Direction.CREDIT)
                ? Math.addExact(balance, amount)
                : Math.subtractExact(balance, amount);
        if (isUserAccount() && next < 0) {
            throw new IllegalStateException("사용자 계정 잔액은 음수일 수 없습니다: " + id);
        }
        this.balance = next;
        return next;
    }

    public void changeStatus(AccountStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus");
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

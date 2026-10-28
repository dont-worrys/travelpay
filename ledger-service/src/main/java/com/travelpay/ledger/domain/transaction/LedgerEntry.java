package com.travelpay.ledger.domain.transaction;

import com.travelpay.ledger.domain.Currency;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 거래의 각 줄. INSERT만 한다. POSTED 거래에만 존재. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry{

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    /** 값 목록은 10/30 취합 후 enum으로 전환 */
    @Column(name = "entry_type", nullable = false, updatable = false, length = 30)
    private String entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 10)
    private Direction direction;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(UUID transactionId, UUID accountId, Currency currency, String entryType,
                        Direction direction, long amount, long balanceAfter) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.entryType = Objects.requireNonNull(entryType, "entryType");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
    }

    public static LedgerEntry create(UUID transactionId, UUID accountId, Currency currency, String entryType,
                                     Direction direction, long amount, long balanceAfter) {
        return new LedgerEntry(transactionId, accountId, currency, entryType,
                direction, amount, balanceAfter);
    }
}



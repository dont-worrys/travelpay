package com.travelpay.wallet.domain.wallet;

import com.travelpay.wallet.domain.Currency;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 지갑 하나에 통화별 1행. 원장 계정과 1:1로 연결된다.
 * 원장 계정 개설이 성공해 ledgerAccountId를 받은 뒤에만 만든다.
 */
@Getter
@Entity
@Table(name = "wallet_currencies")
public class WalletCurrency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "ledger_account_id", nullable = false, updatable = false)
    private UUID ledgerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletCurrency() {
    }

    private WalletCurrency(UUID walletId, Currency currency, UUID ledgerAccountId) {
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.ledgerAccountId = Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
        this.status = WalletStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static WalletCurrency create(UUID walletId, Currency currency, UUID ledgerAccountId) {
        return new WalletCurrency(walletId, currency, ledgerAccountId);
    }

    public void changeStatus(WalletStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus");
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

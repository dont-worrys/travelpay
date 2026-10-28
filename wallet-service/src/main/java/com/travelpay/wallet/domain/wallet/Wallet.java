package com.travelpay.wallet.domain.wallet;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
    }

    private Wallet(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.status = WalletStatus.PENDING;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Wallet create(UUID userId) {
        return new Wallet(userId);
    }

    /** 지갑 개설 재호출 시 이미 ACTIVE면 그대로 둔다 */
    public void activate() {
        if (status == WalletStatus.ACTIVE) {
            return;
        }
        if (status != WalletStatus.PENDING) {
            throw new IllegalStateException("생성 중인 지갑만 활성화할 수 있습니다. 현재 상태: " + status);
        }
        this.status = WalletStatus.ACTIVE;
    }

    public void changeStatus(WalletStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus");
    }

    public boolean isActive() {
        return status == WalletStatus.ACTIVE;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

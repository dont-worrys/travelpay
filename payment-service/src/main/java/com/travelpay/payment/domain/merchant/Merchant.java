package com.travelpay.payment.domain.merchant;

import com.travelpay.payment.domain.payment.CurrencyCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    @Enumerated(EnumType.STRING)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    private MerchantStatus status;

    private UUID ledgerWalletId;

    @Column(nullable = false, updatable = false)
    private UUID ownerUserId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Merchant create(String name, CurrencyCode currency, UUID ownerUserId) {
        Merchant merchant = new Merchant();
        merchant.name = name;
        merchant.currency = currency;
        merchant.ownerUserId = ownerUserId;
        merchant.status = MerchantStatus.PENDING;
        return merchant;
    }

    public boolean isPending() {
        return status == MerchantStatus.PENDING;
    }

    public void activate(UUID ledgerWalletId) {
        if (status != MerchantStatus.PENDING) {
            throw new IllegalStateException("PENDING 가맹점만 활성화할 수 있습니다.");
        }
        this.ledgerWalletId = ledgerWalletId;
        this.status = MerchantStatus.ACTIVE;
    }
}

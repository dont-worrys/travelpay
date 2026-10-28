package com.travelpay.bank.domain.account;


import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 은행 계좌 (사용자 계좌 + 플랫폼 계좌).
 * 잔액 차감은 조건부 UPDATE(WHERE balance >= :amount)로 한다.
 */
@Getter
@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "bank_code", nullable = false, updatable = false, length = 10)
    private String bankCode;

    @Column(name = "account_number", nullable = false, updatable = false, length = 20)
    private String accountNumber;

    @Column(name = "holder_name", nullable = false, length = 100)
    private String holderName;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, updatable = false, length = 20)
    private BankAccountOwnerType ownerType;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BankAccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BankAccount() {
    }

    private BankAccount(String bankCode, String accountNumber, String holderName,
                        BankAccountOwnerType ownerType, long balance) {
        if (balance < 0) {
            throw new IllegalArgumentException("잔액은 음수일 수 없습니다.");
        }
        this.bankCode = Objects.requireNonNull(bankCode, "bankCode");
        this.accountNumber = Objects.requireNonNull(accountNumber, "accountNumber");
        this.holderName = Objects.requireNonNull(holderName, "holderName");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.balance = balance;
        this.status = BankAccountStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 테스트용. 실제 데이터는 시드 SQL로 넣는다. */
    public static BankAccount create(String bankCode, String accountNumber, String holderName,
                                     BankAccountOwnerType ownerType, long balance) {
        return new BankAccount(bankCode, accountNumber, holderName, ownerType, balance);
    }

    public boolean isActive() {
        return status == BankAccountStatus.ACTIVE;
    }

    /** 예금주 일치 판정. 표기가 정확히 같아야 일치로 본다. */
    public boolean holderMatches(String name) {
        return holderName.equals(name);
    }

    public void changeStatus(BankAccountStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus");
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

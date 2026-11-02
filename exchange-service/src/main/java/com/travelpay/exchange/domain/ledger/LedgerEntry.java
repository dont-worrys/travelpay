package com.travelpay.exchange.domain.ledger;

import java.util.Objects;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Money;

/**
 * 원장 거래의 항목 한 줄입니다. 사용자 계정은 지갑 ID로, 회사 계정은 계정 유형으로 지정하며 둘 중 하나만 있습니다.
 * 원장은 금액이 0 이하인 항목을 400으로 거절하므로 금액은 0보다 커야 합니다.
 *
 * @param walletId      사용자 지갑 계정이면 지갑 ID, 회사 계정이면 null
 * @param systemAccount 회사 계정이면 계정 유형, 사용자 계정이면 null
 */
public record LedgerEntry(UUID walletId, LedgerSystemAccount systemAccount, Money money,
                          LedgerDirection direction, LedgerEntryType entryType) {

    public LedgerEntry {
        if ((walletId == null) == (systemAccount == null)) {
            throw new IllegalArgumentException("exactly one of walletId and systemAccount must be set");
        }
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryType, "entryType");
        if (money.isZero()) {
            throw new IllegalArgumentException("ledger entry amount must be positive");
        }
    }

    public static LedgerEntry wallet(UUID walletId, Money money, LedgerDirection direction, LedgerEntryType type) {
        return new LedgerEntry(Objects.requireNonNull(walletId, "walletId"), null, money, direction, type);
    }

    public static LedgerEntry system(LedgerSystemAccount account, Money money, LedgerDirection direction,
                                     LedgerEntryType type) {
        return new LedgerEntry(null, Objects.requireNonNull(account, "account"), money, direction, type);
    }

    public boolean isWalletEntry() {
        return walletId != null;
    }
}

package com.travelpay.ledger.application.transaction.dto;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.LedgerEntryLine;

import java.util.UUID;

/** 항목 한 건. 계정 값은 저장된 그대로, entryType은 문자열 그대로. 거래 조회·기간 목록에서 함께 쓴다 */
public record LedgerEntryResult(OwnerType ownerType, UUID walletId, AccountType accountType, Currency currency,
                                Direction direction, long amount, String entryType) {

    public static LedgerEntryResult from(LedgerEntryLine line) {
        return new LedgerEntryResult(line.ownerType(), line.walletId(), line.accountType(), line.currency(),
                line.direction(), line.amount(), line.entryType());
    }
}

package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.LedgerEntryResult;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;

import java.util.UUID;

/**
 * 원장 항목 한 건. 거래 조회와 기간 목록에서 같은 형식으로 쓴다.
 * 계정 값은 저장된 그대로 (USER: walletId + accountType=WALLET, SYSTEM: walletId=null).
 * entryType은 문자열 그대로. balanceAfter는 내보내지 않는다.
 */
public record LedgerEntryResponse(OwnerType ownerType, UUID walletId, AccountType accountType, Currency currency,
                                  Direction direction, long amount, String entryType) {

    public static LedgerEntryResponse from(LedgerEntryResult entry) {
        return new LedgerEntryResponse(entry.ownerType(), entry.walletId(), entry.accountType(), entry.currency(),
                entry.direction(), entry.amount(), entry.entryType());
    }
}

package com.travelpay.ledger.domain.transaction;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;

import java.util.UUID;

/**
 * 항목 한 줄과 그 항목이 가리키는 계정을 한 번에 읽은 조회 결과.
 * 계정 값은 저장된 그대로다 (USER 계정의 accountType은 WALLET, SYSTEM 계정의 walletId는 null).
 * entryType은 옛 값이 남은 DB에서도 조회가 깨지지 않도록 enum으로 바꾸지 않는다.
 */
public record LedgerEntryLine(OwnerType ownerType, UUID walletId, AccountType accountType, Currency currency,
                              Direction direction, long amount, String entryType) {
}

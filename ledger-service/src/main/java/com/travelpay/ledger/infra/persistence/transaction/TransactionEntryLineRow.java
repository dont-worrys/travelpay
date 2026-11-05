package com.travelpay.ledger.infra.persistence.transaction;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.LedgerEntryLine;

import java.util.UUID;

/** 여러 거래의 항목을 한 번에 읽을 때 거래 id를 함께 받는 행. JPQL 생성자 표현식은 중첩이 안 돼 평평하게 둔다 */
public record TransactionEntryLineRow(UUID transactionId, OwnerType ownerType, UUID walletId,
                                      AccountType accountType, Currency currency, Direction direction,
                                      long amount, String entryType) {

    LedgerEntryLine toLine() {
        return new LedgerEntryLine(ownerType, walletId, accountType, currency, direction, amount, entryType);
    }
}

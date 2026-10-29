package com.travelpay.ledger.application.transaction.dto;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.ReferenceType;

import java.util.List;
import java.util.UUID;

/** 형식 규칙(계정 지정 방식·통화별 차변=대변)은 TransactionCommandValidator가 확인한다 */
public record RegisterTransactionCommand(ReferenceType referenceType, UUID referenceId, UUID userId,
                                         String description, UUID originalTransactionId,
                                         List<Entry> entries) {

    public RegisterTransactionCommand {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** USER는 walletId, SYSTEM은 accountType으로 계정을 지정한다 */
    public record Entry(OwnerType ownerType, UUID walletId, AccountType accountType, Currency currency,
                        Direction direction, long amount, String entryType) {
    }
}

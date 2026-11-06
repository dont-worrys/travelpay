package com.travelpay.wallet.application.reconciliation.dto;

import java.util.Objects;

/** 한 충전에 대해 확인한 은행 출금·은행 환불·원장 사실 */
public record ChargeFacts(SourceLookup<BankTransactionRecord> debit,
                          SourceLookup<BankTransactionRecord> refund,
                          SourceLookup<LedgerTransactionRecord> ledger) {

    public ChargeFacts {
        Objects.requireNonNull(debit, "debit");
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(ledger, "ledger");
    }
}

package com.travelpay.ledger.application.transaction.dto;

import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.LedgerTransactionStatus;
import com.travelpay.ledger.domain.transaction.RejectReason;

import java.util.UUID;

/** created: 이번 요청으로 새로 기록했는지 (기존 결과 반환이면 false) */
public record TransactionResult(UUID transactionId, LedgerTransactionStatus status,
                                RejectReason rejectReason, boolean created) {

    public static TransactionResult of(LedgerTransaction transaction, boolean created) {
        return new TransactionResult(transaction.getId(), transaction.getStatus(),
                transaction.getRejectReason(), created);
    }

    public boolean isPosted() {
        return status == LedgerTransactionStatus.POSTED;
    }
}

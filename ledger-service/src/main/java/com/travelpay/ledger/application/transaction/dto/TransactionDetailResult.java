package com.travelpay.ledger.application.transaction.dto;

import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.LedgerTransactionStatus;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import com.travelpay.ledger.domain.transaction.RejectReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 거래 조회 결과. REJECTED 거래는 항목을 저장하지 않으므로 entries가 비어 있다 */
public record TransactionDetailResult(UUID transactionId, LedgerTransactionStatus status, RejectReason rejectReason,
                                      ReferenceType referenceType, UUID referenceId, Instant createdAt,
                                      List<LedgerEntryResult> entries) {

    public TransactionDetailResult {
        entries = List.copyOf(entries);
    }

    public static TransactionDetailResult of(LedgerTransaction transaction, List<LedgerEntryResult> entries) {
        return new TransactionDetailResult(transaction.getId(), transaction.getStatus(),
                transaction.getRejectReason(), transaction.getReferenceType(), transaction.getReferenceId(),
                transaction.getCreatedAt(), entries);
    }
}

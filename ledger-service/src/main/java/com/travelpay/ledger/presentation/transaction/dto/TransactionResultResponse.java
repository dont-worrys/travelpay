package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.TransactionDetailResult;
import com.travelpay.ledger.domain.transaction.LedgerTransactionStatus;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import com.travelpay.ledger.domain.transaction.RejectReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** rejectReason은 REJECTED일 때만 값이 있음. REJECTED 거래는 항목이 없어 entries가 빈 배열 */
public record TransactionResultResponse(UUID transactionId, LedgerTransactionStatus status,
                                        RejectReason rejectReason, ReferenceType referenceType, UUID referenceId,
                                        Instant createdAt, List<LedgerEntryResponse> entries) {

    public static TransactionResultResponse from(TransactionDetailResult result) {
        return new TransactionResultResponse(result.transactionId(), result.status(), result.rejectReason(),
                result.referenceType(), result.referenceId(), result.createdAt(),
                result.entries().stream().map(LedgerEntryResponse::from).toList());
    }
}

package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.TransactionResult;
import com.travelpay.ledger.domain.transaction.LedgerTransactionStatus;
import com.travelpay.ledger.domain.transaction.RejectReason;

import java.util.UUID;

/** rejectReason은 REJECTED일 때만 값이 있음 */
public record TransactionResultResponse(UUID transactionId, LedgerTransactionStatus status,
                                        RejectReason rejectReason) {

    public static TransactionResultResponse from(TransactionResult result) {
        return new TransactionResultResponse(result.transactionId(), result.status(), result.rejectReason());
    }
}

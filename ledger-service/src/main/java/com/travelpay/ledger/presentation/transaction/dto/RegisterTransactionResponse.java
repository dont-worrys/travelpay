package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.TransactionResult;
import com.travelpay.ledger.domain.transaction.LedgerTransactionStatus;

import java.util.UUID;

public record RegisterTransactionResponse(UUID transactionId, LedgerTransactionStatus status) {

    public static RegisterTransactionResponse from(TransactionResult result) {
        return new RegisterTransactionResponse(result.transactionId(), result.status());
    }
}

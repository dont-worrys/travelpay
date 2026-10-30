package com.travelpay.bank.presentation.transaction.dto;

import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.transaction.BankFailureCode;
import com.travelpay.bank.domain.transaction.BankTransactionStatus;
import com.travelpay.bank.domain.transaction.BankTransactionType;

import java.time.Instant;
import java.util.UUID;

public record BankTransactionResponse(
        UUID bankTxId,
        String requestId,
        BankTransactionType type,
        BankTransactionStatus status,
        BankFailureCode failureCode,
        long amount,
        UUID originalTxId,
        Instant completedAt
) {

    public static BankTransactionResponse from(BankTransactionResult result) {
        return new BankTransactionResponse(
                result.bankTxId(),
                result.requestId(),
                result.type(),
                result.status(),
                result.failureCode(),
                result.amount(),
                result.originalTxId(),
                result.completedAt()
        );
    }
}

package com.travelpay.bank.application.transaction.dto;

public record BankWithdrawalCommand(
        String requestId,
        String bankCode,
        String accountNumber,
        String holderName,
        long amount
) {
}

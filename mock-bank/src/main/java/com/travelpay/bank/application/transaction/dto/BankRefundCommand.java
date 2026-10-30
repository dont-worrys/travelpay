package com.travelpay.bank.application.transaction.dto;

import java.util.UUID;

public record BankRefundCommand(
        String requestId,
        UUID originalTxId,
        long amount
) {
}

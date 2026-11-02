package com.travelpay.payment.application.payment.client.ledger;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record LedgerTransactionCommand(
        UUID referenceId,        // payment.id → ledger 멱등키
        UUID userId,
        UUID userWalletId,       // 차감할 사용자 지갑
        UUID merchantWalletId,   // 적립할 가맹점 원장 지갑
        CurrencyCode currency,
        long amount
) {
}

package com.travelpay.payment.application.payment.client.ledger;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record LedgerTransactionCommand(
        UUID debitAccountId,     // 차감할 원장 계정 (지갑 조회 결과)
        CurrencyCode currency,
        Long amount,
        String referenceType,    // "PAYMENT"
        UUID referenceId,        // payment.id → ledger 멱등키
        UUID merchantId
) {
}
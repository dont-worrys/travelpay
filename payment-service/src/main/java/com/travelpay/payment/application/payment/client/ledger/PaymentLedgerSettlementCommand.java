package com.travelpay.payment.application.payment.client.ledger;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record PaymentLedgerSettlementCommand(
        UUID referenceId,        // settlement.id → ledger 멱등키
        UUID ownerUserId,        // 정산을 받는 가맹점 주인
        UUID merchantWalletId,   // 차감할 가맹점 원장 지갑
        UUID ownerWalletId,      // 적립할 주인 지갑
        CurrencyCode currency,
        long amount
) {
}

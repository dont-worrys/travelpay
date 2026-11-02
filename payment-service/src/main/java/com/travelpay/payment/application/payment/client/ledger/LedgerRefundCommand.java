package com.travelpay.payment.application.payment.client.ledger;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record LedgerRefundCommand(
        UUID referenceId,            // refund.id → ledger 환불 멱등키
        UUID originalTransactionId,  // 되돌릴 결제 거래 (payment.ledgerId)
        UUID userId,
        UUID userWalletId,           // 돌려받을 사용자 지갑
        UUID merchantWalletId,       // 차감할 가맹점 원장 지갑
        CurrencyCode currency,
        long amount
) {
}

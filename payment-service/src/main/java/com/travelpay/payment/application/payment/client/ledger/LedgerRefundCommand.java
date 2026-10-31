package com.travelpay.payment.application.payment.client.ledger;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record LedgerRefundCommand(
        UUID originalTransactionId,  // 되돌릴 결제 거래 (payment.ledgerId)
        CurrencyCode currency,
        Long amount,
        String referenceType,        // "PAYMENT_REFUND"
        UUID referenceId             // refund.id → ledger 환불 멱등키
) {
}

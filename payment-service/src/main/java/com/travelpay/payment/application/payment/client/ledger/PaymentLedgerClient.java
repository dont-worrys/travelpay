package com.travelpay.payment.application.payment.client.ledger;

import java.util.UUID;

public interface PaymentLedgerClient {
    PaymentLedgerResult createTransaction(PaymentLedgerTransactionCommand command);
    PaymentLedgerResult refundTransaction(PaymentLedgerRefundCommand command);
    PaymentLedgerResult settlementTransaction(PaymentLedgerSettlementCommand command);

    /** 원장을 조회하지 못하면 LEDGER_LOOKUP_UNAVAILABLE(503) */
    PaymentLedgerLookup findPaymentTransaction(UUID paymentId);
    PaymentLedgerLookup findRefundTransaction(UUID refundId);
}

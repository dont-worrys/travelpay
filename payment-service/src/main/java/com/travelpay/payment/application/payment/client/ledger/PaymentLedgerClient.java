package com.travelpay.payment.application.payment.client.ledger;

public interface PaymentLedgerClient {
    PaymentLedgerResult createTransaction(PaymentLedgerTransactionCommand command);
    PaymentLedgerResult refundTransaction(PaymentLedgerRefundCommand command);
}

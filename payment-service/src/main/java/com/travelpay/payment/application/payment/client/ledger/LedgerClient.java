package com.travelpay.payment.application.payment.client.ledger;

public interface LedgerClient {
    LedgerResult createTransaction(LedgerTransactionCommand command);
    LedgerResult refundTransaction(LedgerRefundCommand command);
}

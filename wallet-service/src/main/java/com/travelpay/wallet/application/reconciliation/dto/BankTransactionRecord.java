package com.travelpay.wallet.application.reconciliation.dto;

import java.util.UUID;

/**
 * 대사에 쓰는 은행 거래 사실. type(DEBIT·REFUND)·status(SUCCEEDED·FAILED)는 은행 값 그대로.
 * bankTxId는 실패 거래면 없을 수 있다.
 */
public record BankTransactionRecord(String requestId, UUID bankTxId, String type, String status, long amount) {

    private static final String SUCCEEDED = "SUCCEEDED";

    public boolean isSucceeded() {
        return SUCCEEDED.equals(status);
    }
}

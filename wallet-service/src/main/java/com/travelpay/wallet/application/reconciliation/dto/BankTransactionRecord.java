package com.travelpay.wallet.application.reconciliation.dto;

import java.util.UUID;

/**
 * 대사·보정에 쓰는 은행 거래 사실. type(DEBIT·REFUND)·status(SUCCEEDED·FAILED)·failureCode는 은행 값 그대로.
 * bankTxId는 실패 거래면 없을 수 있고, failureCode는 실패 거래만 있다 (대사 판정은 쓰지 않음)
 */
public record BankTransactionRecord(String requestId, UUID bankTxId, String type, String status, long amount,
                                    String failureCode) {

    private static final String SUCCEEDED = "SUCCEEDED";

    /** 실패 코드 없는 기록 */
    public BankTransactionRecord(String requestId, UUID bankTxId, String type, String status, long amount) {
        this(requestId, bankTxId, type, status, amount, null);
    }

    public boolean isSucceeded() {
        return SUCCEEDED.equals(status);
    }
}

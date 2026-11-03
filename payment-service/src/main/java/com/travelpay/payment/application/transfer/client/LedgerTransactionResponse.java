package com.travelpay.payment.application.transfer.client;

import java.util.UUID;

/**
 * 원장 거래 등록 결과입니다. 원장 결과 조회 응답({transactionId, status, rejectReason})과 같은 형식으로 두었습니다.
 * rejectReason은 REJECTED일 때만 있습니다.
 * 거절 사유는 원장 422 거절 코드 세 가지(LEDGER_REJECTED_INSUFFICIENT_BALANCE·_WALLET_SUSPENDED·_ACCOUNT_NOT_FOUND)와 대응합니다
 * (원장 담당자 확인, 2026-11-02). 수취인 계정이 정지된 경우 CREDIT도 WALLET_SUSPENDED로 거절됩니다.
 */
public record LedgerTransactionResponse(UUID transactionId, Status status, RejectReason rejectReason) {

    public enum Status {
        POSTED,
        REJECTED
    }

    public enum RejectReason {
        INSUFFICIENT_BALANCE,
        WALLET_SUSPENDED,
        ACCOUNT_NOT_FOUND
    }

    public static LedgerTransactionResponse posted(UUID transactionId) {
        return new LedgerTransactionResponse(transactionId, Status.POSTED, null);
    }

    public static LedgerTransactionResponse rejected(UUID transactionId, RejectReason rejectReason) {
        return new LedgerTransactionResponse(transactionId, Status.REJECTED, rejectReason);
    }
}

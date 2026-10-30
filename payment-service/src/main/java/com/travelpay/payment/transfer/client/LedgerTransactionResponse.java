package com.travelpay.payment.transfer.client;

import java.util.UUID;

/**
 * 원장 거래 등록 결과입니다. 원장 결과 조회 응답({transactionId, status, rejectReason})과 같은 형식으로 두었습니다.
 * rejectReason은 REJECTED일 때만 있습니다.
 * 미확정: 상태·거절 사유 값은 현재 ledger-service 코드(LedgerTransactionStatus, RejectReason) 기준이며 원장 연동 시 다시 확인합니다.
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

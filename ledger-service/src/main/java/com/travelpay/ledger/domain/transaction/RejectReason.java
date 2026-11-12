package com.travelpay.ledger.domain.transaction;

public enum RejectReason {
    INSUFFICIENT_BALANCE,
    WALLET_SUSPENDED,
    ACCOUNT_NOT_FOUND,
    /** 요청한 서비스가 취소 API로 남긴 기록. 원장이 판단한 거절이 아니며 항목·잔액 변화가 없다 */
    CANCELLED_BY_REQUESTER
}

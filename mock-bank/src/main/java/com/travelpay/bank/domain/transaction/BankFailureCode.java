package com.travelpay.bank.domain.transaction;

/** 은행 업무 거절 사유. 거절도 200 + FAILED로 응답하고 기록을 남긴다. */
public enum BankFailureCode {
    // 출금
    ACCOUNT_NOT_FOUND,
    ACCOUNT_SUSPENDED,
    HOLDER_MISMATCH,
    INSUFFICIENT_BALANCE,
    // 환불
    ORIGINAL_TX_NOT_FOUND,      // 원거래 없음
    ORIGINAL_TX_NOT_REFUNDABLE, // 성공한 DEBIT이 아님
    ALREADY_REFUNDED,           // 원거래당 1회
    AMOUNT_MISMATCH             // 전액만 환불 가능
}

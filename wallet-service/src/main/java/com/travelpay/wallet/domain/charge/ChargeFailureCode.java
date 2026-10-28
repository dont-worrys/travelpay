package com.travelpay.wallet.domain.charge;

public enum ChargeFailureCode {
    BANK_INSUFFICIENT_BALANCE, // 은행 계좌 잔액 부족
    BANK_ACCOUNT_SUSPENDED,    // 은행 계좌 거래 정지
    BANK_ACCOUNT_NOT_FOUND,    // 은행 계좌 없음
    BANK_HOLDER_MISMATCH,      // 예금주 불일치
    LEDGER_REJECTED,           // 원장 처리 거부
    RETRY_EXHAUSTED            // 재시도 횟수 초과
}

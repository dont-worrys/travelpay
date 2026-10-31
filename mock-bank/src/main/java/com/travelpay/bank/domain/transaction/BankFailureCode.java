package com.travelpay.bank.domain.transaction;

/** 은행 업무 거절 사유. 거절도 200 + FAILED로 응답하고 기록을 남긴다. */
public enum BankFailureCode {
    // 출금
    ACCOUNT_NOT_FOUND(BankTransactionType.DEBIT),
    ACCOUNT_SUSPENDED(BankTransactionType.DEBIT),
    HOLDER_MISMATCH(BankTransactionType.DEBIT),
    INSUFFICIENT_BALANCE(BankTransactionType.DEBIT),

    // 환불
    ORIGINAL_TX_NOT_FOUND(BankTransactionType.REFUND),       // 원거래 없음
    ORIGINAL_TX_NOT_REFUNDABLE(BankTransactionType.REFUND),  // 성공한 출금이 아님
    ALREADY_REFUNDED(BankTransactionType.REFUND),            // 원거래당 1회
    AMOUNT_MISMATCH(BankTransactionType.REFUND);             // 전액만 환불 가능

    private final BankTransactionType type;

    BankFailureCode(BankTransactionType type) {
        this.type = type;
    }

    /** 이 실패 코드를 해당 거래 유형에 쓸 수 있는지 */
    public boolean appliesTo(BankTransactionType type) {
        return this.type == type;
    }
}

package com.travelpay.bank.domain.transaction;

/** 원거래에 성공한 환불이 먼저 기록됐다 (uk_bank_transactions_refund_once 위반 또는 처리 중 확인). */
public class BankDuplicateRefundException extends RuntimeException {

    public BankDuplicateRefundException() {
        super("원거래에 성공한 환불이 이미 있습니다.");
    }

    public BankDuplicateRefundException(Throwable cause) {
        super("원거래에 성공한 환불이 이미 있습니다.", cause);
    }
}

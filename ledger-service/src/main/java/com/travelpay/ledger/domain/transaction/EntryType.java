package com.travelpay.ledger.domain.transaction;

/** 원장 거래 항목 유형. 도메인별로 합의한 값이며 V5 CHECK 제약과 같아야 합니다. */
public enum EntryType {
    CHARGE_OUT, CHARGE_IN,
    PAYMENT_OUT, PAYMENT_IN,
    PAYMENT_REFUND_OUT, PAYMENT_REFUND_IN,
    EXCHANGE_OUT, EXCHANGE_IN, EXCHANGE_FEE,
    TRANSFER_OUT, TRANSFER_IN
}

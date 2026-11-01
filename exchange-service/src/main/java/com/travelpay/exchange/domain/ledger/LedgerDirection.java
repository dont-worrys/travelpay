package com.travelpay.exchange.domain.ledger;

/** 원장 항목 방향입니다. 원장 계약상 CREDIT은 잔액 증가, DEBIT은 잔액 감소입니다. */
public enum LedgerDirection {
    DEBIT,
    CREDIT
}

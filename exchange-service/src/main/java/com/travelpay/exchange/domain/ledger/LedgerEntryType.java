package com.travelpay.exchange.domain.ledger;

/** 환전 분개 항목 유형입니다. 원장 담당과 합의한 값이며, 유형마다 차변 합과 대변 합이 같습니다. */
public enum LedgerEntryType {
    /** 보내는 금액: 사용자 지갑(보내는 통화) DEBIT ↔ FX_POSITION(보내는 통화) CREDIT */
    EXCHANGE_OUT,
    /** 수수료: 사용자 지갑(보내는 통화) DEBIT ↔ FEE_REVENUE(보내는 통화) CREDIT */
    EXCHANGE_FEE,
    /** 받는 금액: FX_POSITION(받는 통화) DEBIT ↔ 사용자 지갑(받는 통화) CREDIT */
    EXCHANGE_IN
}

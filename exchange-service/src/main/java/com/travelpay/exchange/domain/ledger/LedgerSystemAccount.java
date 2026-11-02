package com.travelpay.exchange.domain.ledger;

/** 환전이 쓰는 회사 쪽 원장 계정입니다. 통화별 계정은 ledger-service V4 seed로 만들어 둡니다. */
public enum LedgerSystemAccount {
    /** 회사 통화 보유분 */
    FX_POSITION,
    /** 수수료 수익 */
    FEE_REVENUE
}

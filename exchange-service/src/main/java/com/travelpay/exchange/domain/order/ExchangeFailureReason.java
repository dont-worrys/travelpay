package com.travelpay.exchange.domain.order;

/** 환전 주문 실패 사유입니다. 원장 거절 사유와 같은 값을 써서 대사할 때 그대로 비교합니다. */
public enum ExchangeFailureReason {
    /** 지갑 잔액이 수수료 포함 금액보다 적음 */
    INSUFFICIENT_BALANCE,
    /** 원장 계정이 정지됨 */
    WALLET_SUSPENDED,
    /** 원장 계정을 찾지 못함 */
    ACCOUNT_NOT_FOUND
}

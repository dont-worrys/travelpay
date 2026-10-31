package com.travelpay.exchange.domain.order;

/** 환전 주문 상태입니다. 원장 결과에 따라 PROCESSING에서 COMPLETED 또는 FAILED로 한 번만 바뀝니다. */
public enum ExchangeOrderStatus {
    /** 주문 저장 후 원장 결과를 기다리는 중. 결과를 모르면 이 상태로 남고 복구 대상이 됩니다. */
    PROCESSING,
    /** 원장에 거래가 기록됨 */
    COMPLETED,
    /** 원장이 거래를 거절함 */
    FAILED
}

package com.travelpay.exchange.domain.order;

/**
 * 환전 주문 상태입니다. PROCESSING에서 원장 결과에 따라 한 번만 바뀝니다.
 * COMPLETED·FAILED는 확정, MANUAL_REVIEW는 자동으로 결론을 낼 수 없어 사람이 확인하는 상태입니다.
 */
public enum ExchangeOrderStatus {
    /** 주문 저장 후 원장 결과를 기다리는 중. 결과를 모르면 이 상태로 남고 복구 대상이 됩니다. */
    PROCESSING,
    /** 원장에 거래가 기록됨 */
    COMPLETED,
    /** 원장이 거래를 거절함 */
    FAILED,
    /** 원장이 다시 보내도 풀리지 않는 응답(400·409, 모르는 거절 사유)을 줬거나 오래 결과를 확인하지 못함. 복구 대상에서 빠짐 */
    MANUAL_REVIEW;

    /** 결과가 확정된 상태입니다. 확정 시각(settled_at)은 이 상태에만 있습니다. */
    public boolean isSettled() {
        return this == COMPLETED || this == FAILED;
    }
}

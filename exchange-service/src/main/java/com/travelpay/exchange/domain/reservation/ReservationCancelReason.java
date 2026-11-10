package com.travelpay.exchange.domain.reservation;

/** 예약이 취소된 이유입니다. 취소(CANCELED)된 예약에만 있습니다. */
public enum ReservationCancelReason {
    /** 사용자가 취소 */
    USER_REQUESTED,
    /** 체결하려 할 때 지갑이 없거나(해지) 그 통화로 환전할 수 없는 상태 */
    WALLET_UNAVAILABLE,
    /** 체결 시점 규칙으로 주문을 만들 수 없음. 예: 예약 뒤 1회 한도가 바뀌어 금액이 범위 밖 */
    EXECUTION_REJECTED
}

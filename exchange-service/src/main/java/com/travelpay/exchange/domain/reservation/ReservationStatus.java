package com.travelpay.exchange.domain.reservation;

/** 예약 환전 상태입니다. WAITING에서만 다른 상태로 바뀌고, 나머지 셋은 끝난 상태입니다. */
public enum ReservationStatus {
    /** 목표 환율을 기다리는 중. 체결·취소·만료될 수 있음 */
    WAITING,
    /** 체결됨: 환전 주문을 만듦. 환전 결과(완료·실패)는 주문 상태로 확인 */
    EXECUTED,
    /** 사용자가 취소했거나 지갑이 해지됨 */
    CANCELED,
    /** 만료 시각까지 목표 환율에 닿지 않음 */
    EXPIRED
}

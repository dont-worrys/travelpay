package com.travelpay.exchange.domain.order;

/** 주문 상태를 바꾼 주체입니다. */
public enum ChangedByType {
    /** 회원의 환전 신청 */
    USER,
    /** 원장 결과 반영·복구 스케줄러 */
    SYSTEM
}

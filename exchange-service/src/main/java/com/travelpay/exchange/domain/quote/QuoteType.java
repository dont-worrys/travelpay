package com.travelpay.exchange.domain.quote;

/** 견적을 발급한 계기입니다. */
public enum QuoteType {
    /** 사용자가 요청한 견적 */
    USER,
    /** 예약 환전 체결 시 시스템이 체결 시점 환율로 발급한 견적 */
    RESERVATION
}

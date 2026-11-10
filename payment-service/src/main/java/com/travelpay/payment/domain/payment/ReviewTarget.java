package com.travelpay.payment.domain.payment;

/** 운영자 처리 기록의 대상. 환불 처리 기록은 refundId가 있습니다. */
public enum ReviewTarget {
    PAYMENT, REFUND
}

package com.travelpay.payment.domain.payment;

/**
 * MANUAL_REVIEW 처리 근거가 된 원장 조회 결과입니다.
 * POSTED·REJECTED: 원장에 같은 reference의 거래가 있음 / NOT_FOUND: 원장에 기록 없음
 */
public enum ReviewLedgerStatus {
    POSTED, REJECTED, NOT_FOUND
}

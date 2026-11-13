package com.travelpay.payment.application.payment;

/** MANUAL_REVIEW 자동 처리 한 건의 결과 */
public enum AutoResolveOutcome {
    /** 원장 결과로 상태를 확정함 */
    RESOLVED,
    /** 자동 처리 대상이 아님(원장 기록 없음·원장 항목 불일치). MANUAL_REVIEW 그대로 운영자에게 남김 */
    SKIPPED,
    /** 원장을 조회하는 사이 운영자 등이 먼저 처리함 */
    ALREADY_RESOLVED
}

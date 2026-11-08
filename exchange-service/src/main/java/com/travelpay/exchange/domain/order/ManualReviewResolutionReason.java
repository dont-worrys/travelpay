package com.travelpay.exchange.domain.order;

/**
 * 관리자(MASTER)가 MANUAL_REVIEW 주문을 처리할 때 상태 이력(exchange_order_status_histories.reason)에
 * 시스템이 남기는 판단 근거입니다.
 */
public enum ManualReviewResolutionReason {
    /** 원장에 주문과 같은 항목으로 POSTED되어 있어 COMPLETED로 확정 */
    LEDGER_POSTED,
    /** 원장에 REJECTED로 기록되어 있어 FAILED로 확정 */
    LEDGER_REJECTED,
    /** 원장에 거래 기록이 없어(404 LEDGER_TRANSACTION_NOT_FOUND) 시간 초과 취소(FAILED)로 확정 */
    LEDGER_NOT_FOUND
}

package com.travelpay.payment.domain.payment;

/**
 * 결제·환불 자동 재시도를 멈추고 운영자 확인(MANUAL_REVIEW)으로 넘긴 사유입니다. MANUAL_REVIEW일 때만 있습니다.
 * 두 경우 모두 원장 반영 여부가 확정되지 않았으므로 DECLINED로 끝내지 않습니다.
 */
public enum SettlementReviewReason {
    /** 원장이 요청을 받아들이지 않았습니다(409·400 등). 409는 같은 referenceId의 원장 거래가 이미 있다는 뜻일 수 있습니다. */
    LEDGER_REQUEST_NOT_ACCEPTED,
    /** 원장 요청 결과를 한도만큼 확인하지 못했습니다. */
    RETRY_EXHAUSTED
}

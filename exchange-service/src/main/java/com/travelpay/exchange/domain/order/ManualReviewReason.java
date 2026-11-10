package com.travelpay.exchange.domain.order;

/** 주문을 사람 확인(MANUAL_REVIEW)으로 보낸 이유입니다. 상태 이력의 reason에 남깁니다. */
public enum ManualReviewReason {
    /** 원장 400: 우리 요청 형식 오류. 원장에 기록되지 않았으므로 고친 뒤 PROCESSING으로 되돌리면 다시 보낼 수 있음 */
    LEDGER_BAD_REQUEST,
    /** 원장 409: 같은 주문 ID로 다른 내용의 거래가 이미 있음 */
    LEDGER_CONFLICT,
    /** 해석할 수 없는 원장 응답 */
    LEDGER_MALFORMED_RESPONSE,
    /** 원장이 우리가 모르는 사유로 거절함 */
    LEDGER_UNKNOWN_REJECTION,
    /** 오래(신청 후 24시간) 원장 결과를 확인하지 못함 */
    LEDGER_UNCONFIRMED_TOO_LONG
}

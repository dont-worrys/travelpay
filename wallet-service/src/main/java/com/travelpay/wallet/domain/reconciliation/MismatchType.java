package com.travelpay.wallet.domain.reconciliation;

public enum MismatchType {
    /** 은행에서 돈이 빠졌는데 지갑 적립·환불로 돌려주지 않음 */
    BANK_DEBIT_WITHOUT_CREDIT,
    /** 지갑에 적립됐는데 은행 출금 성공이 없음 */
    CREDIT_WITHOUT_BANK_DEBIT,
    /** 지갑 적립과 은행 환불이 모두 있음 */
    CREDIT_AND_REFUND_BOTH,
    /** 은행·원장 금액(통화)이 충전 금액(통화)과 다름 */
    AMOUNT_MISMATCH,
    /** 충전 상태 또는 저장된 ID가 은행·원장 사실과 다름 (detail에 구분) */
    STATUS_MISMATCH,
    /** 은행 거래에 대응하는 충전이 없음 */
    BANK_ORPHAN,
    /** 원장 거래에 대응하는 충전이 없음 */
    LEDGER_ORPHAN,
    /** MANUAL_REVIEW 충전. 판정하지 않고 사실만 남김 */
    REVIEW_REQUIRED,
    /** 조회 실패로 일부 판정을 보류함 */
    SOURCE_UNAVAILABLE
}

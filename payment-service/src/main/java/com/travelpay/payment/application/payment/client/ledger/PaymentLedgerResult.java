package com.travelpay.payment.application.payment.client.ledger;

import java.util.UUID;

public record PaymentLedgerResult(Status status, UUID transactionId, String declineReason) {

    /**
     * APPROVED·DECLINED: 원장이 확정한 결과
     * NOT_ACCEPTED: 원장이 요청을 받아들이지 않음(409·400 등, 알 수 없는 422 코드). 재요청해도 같은 결과라 자동 처리를 멈춤
     * UNKNOWN: 결과를 확인하지 못함(시간 초과·연결 실패·5xx·응답 해석 불가). 같은 referenceId로 다시 요청
     */
    public enum Status {APPROVED, DECLINED, NOT_ACCEPTED, UNKNOWN}

    public static PaymentLedgerResult approved(UUID transactionId) {
        return new PaymentLedgerResult(Status.APPROVED, transactionId, null);
    }

    public static PaymentLedgerResult declined(String declineReason) {
        return new PaymentLedgerResult(Status.DECLINED, null, declineReason);
    }

    public static PaymentLedgerResult notAccepted() {
        return new PaymentLedgerResult(Status.NOT_ACCEPTED, null, null);
    }

    public static PaymentLedgerResult unknown() {
        return new PaymentLedgerResult(Status.UNKNOWN, null, null);
    }
}

package com.travelpay.payment.transfer.domain;

/**
 * 원장이 송금을 확정 거절한 사유입니다. FAILED 송금에만 있으며, 같은 키 재요청에 처음과 같은 오류로 응답하는 데 씁니다.
 * ACCOUNT_UNAVAILABLE은 원장의 계정 정지(WALLET_SUSPENDED)·계정 없음(ACCOUNT_NOT_FOUND) 거절이며, 송금인·수취인 중 어느 쪽인지는 알 수 없습니다.
 * NOT_PROCESSED는 원장이 요청을 받지 않은 송금(LEDGER_REQUEST_NOT_ACCEPTED)을 운영자가 FAILED로 확정한 경우입니다.
 * 원장의 거절 기록은 이전에 들어온 다른 요청의 것일 수 있어 그 거절 사유를 쓰지 않고 송금 쪽에서 정합니다(자금은 이동하지 않음).
 */
public enum TransferFailureReason {
    INSUFFICIENT_BALANCE(TransferErrorCode.INSUFFICIENT_BALANCE),
    ACCOUNT_UNAVAILABLE(TransferErrorCode.ACCOUNT_UNAVAILABLE),
    NOT_PROCESSED(TransferErrorCode.NOT_PROCESSED);

    private final TransferErrorCode errorCode;

    TransferFailureReason(TransferErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public TransferErrorCode errorCode() {
        return errorCode;
    }
}

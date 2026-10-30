package com.travelpay.payment.transfer.client;

/**
 * 지갑 송금 처리 결과입니다. 확정 거절 사유는 잔액 부족만 다루며,
 * 다른 거절 사유를 추가하면 FAILED 송금의 재요청 응답을 위해 실패 사유 저장도 함께 검토해야 합니다.
 */
public enum WalletTransferResult {
    SUCCEEDED,
    INSUFFICIENT_BALANCE,
    UNKNOWN
}

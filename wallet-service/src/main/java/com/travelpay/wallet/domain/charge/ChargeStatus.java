package com.travelpay.wallet.domain.charge;

public enum ChargeStatus {
    REQUESTED(ChargeDisplayStatus.PROCESSING),
    BANK_PROCESSING(ChargeDisplayStatus.PROCESSING),
    CREDIT_PROCESSING(ChargeDisplayStatus.PROCESSING),
    COMPLETED(ChargeDisplayStatus.COMPLETED),
    FAILED(ChargeDisplayStatus.FAILED),
    REFUND_PROCESSING(ChargeDisplayStatus.PROCESSING),
    REFUNDED(ChargeDisplayStatus.REFUNDED),
    MANUAL_REVIEW(ChargeDisplayStatus.REVIEWING);

    private final ChargeDisplayStatus displayStatus;

    ChargeStatus(ChargeDisplayStatus displayStatus) {
        this.displayStatus = displayStatus;
    }

    public ChargeDisplayStatus displayStatus() {
        return displayStatus;
    }

    /** 같은 멱등 키 재요청 시: 처리 중이면 202, 아니면 200 */
    public boolean isInProgress() {
        return displayStatus == ChargeDisplayStatus.PROCESSING;
    }
}

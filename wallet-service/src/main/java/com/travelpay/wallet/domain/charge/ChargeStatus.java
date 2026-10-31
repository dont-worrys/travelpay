package com.travelpay.wallet.domain.charge;

import java.util.EnumSet;
import java.util.Set;

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

    /** 이 상태에서 바로 갈 수 있는 상태. 종료 상태는 비어 있다 */
    public Set<ChargeStatus> nextStatuses() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(BANK_PROCESSING);
            case BANK_PROCESSING -> EnumSet.of(CREDIT_PROCESSING, FAILED, MANUAL_REVIEW);
            case CREDIT_PROCESSING -> EnumSet.of(COMPLETED, REFUND_PROCESSING, MANUAL_REVIEW);
            case REFUND_PROCESSING -> EnumSet.of(REFUNDED, MANUAL_REVIEW);
            case COMPLETED, FAILED, REFUNDED, MANUAL_REVIEW -> EnumSet.noneOf(ChargeStatus.class);
        };
    }

    public boolean canTransitionTo(ChargeStatus to) {
        return nextStatuses().contains(to);
    }

    /** 같은 멱등 키 재요청 시: 처리 중이면 202, 아니면 200 */
    public boolean isInProgress() {
        return displayStatus == ChargeDisplayStatus.PROCESSING;
    }
}

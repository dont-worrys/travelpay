package com.travelpay.wallet.domain.resolution;

import com.travelpay.wallet.domain.charge.ChargeStatus;

/** MANUAL_REVIEW 충전의 운영자 보정 조치. 돈을 직접 움직이지 않고 상태를 확정하거나 기존 SAGA 단계로 되돌린다 */
public enum ResolutionAction {
    CONFIRM_COMPLETED(ChargeStatus.COMPLETED),
    CONFIRM_FAILED(ChargeStatus.FAILED),
    CONFIRM_REFUNDED(ChargeStatus.REFUNDED),
    /** 원장 적립 단계로 재개 (attempt 0) */
    RESUME_CREDIT(ChargeStatus.CREDIT_PROCESSING),
    /** 은행 환불 단계로 재개 (attempt 0) */
    RESUME_REFUND(ChargeStatus.REFUND_PROCESSING);

    private final ChargeStatus targetStatus;

    ResolutionAction(ChargeStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    public ChargeStatus targetStatus() {
        return targetStatus;
    }

    /** 상태 이력 reason_code */
    public String historyReasonCode() {
        return "RESOLVE_" + name();
    }
}

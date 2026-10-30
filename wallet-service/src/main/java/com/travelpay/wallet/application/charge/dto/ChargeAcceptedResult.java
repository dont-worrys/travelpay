package com.travelpay.wallet.application.charge.dto;

import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.util.UUID;

public record ChargeAcceptedResult(UUID chargeId, ChargeStatus status) {

    /** 처리 중이면 202, 끝났으면(MANUAL_REVIEW 포함) 200으로 응답한다 */
    public boolean inProgress() {
        return status.isInProgress();
    }
}

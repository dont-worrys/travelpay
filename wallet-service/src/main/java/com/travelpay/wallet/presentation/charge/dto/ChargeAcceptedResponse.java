package com.travelpay.wallet.presentation.charge.dto;

import com.travelpay.wallet.application.charge.dto.ChargeAcceptedResult;
import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.util.UUID;

public record ChargeAcceptedResponse(UUID chargeId, ChargeStatus status) {

    public static ChargeAcceptedResponse from(ChargeAcceptedResult result) {
        return new ChargeAcceptedResponse(result.chargeId(), result.status());
    }
}

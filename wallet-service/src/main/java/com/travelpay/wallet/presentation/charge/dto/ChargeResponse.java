package com.travelpay.wallet.presentation.charge.dto;

import com.travelpay.wallet.application.charge.dto.ChargeResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.time.Instant;
import java.util.UUID;

/** 시간은 UTC ISO-8601(Z). 계좌번호는 마스킹 값만 */
public record ChargeResponse(UUID chargeId, ChargeStatus status, long amount, Currency currency,
                             String bankCode, String accountNumberMask,
                             ChargeFailureCode failureCode, String failureMessage,
                             Instant createdAt, Instant updatedAt) {

    public static ChargeResponse from(ChargeResult result) {
        return new ChargeResponse(result.chargeId(), result.status(), result.amount(), result.currency(),
                result.bankCode(), result.accountNumberMask(), result.failureCode(), result.failureMessage(),
                result.createdAt(), result.updatedAt());
    }
}

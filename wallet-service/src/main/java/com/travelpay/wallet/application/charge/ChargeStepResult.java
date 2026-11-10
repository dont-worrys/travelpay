package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;

import java.util.Objects;
import java.util.UUID;

/** 단계 전이와 함께 저장할 값. null인 값은 기존 값을 유지한다 */
public record ChargeStepResult(ChargeFailureCode failureCode, String lastError,
                               UUID bankTxId, UUID ledgerRef, UUID refundBankTxId) {

    public static ChargeStepResult none() {
        return new ChargeStepResult(null, null, null, null, null);
    }

    public static ChargeStepResult withdrawn(UUID bankTxId) {
        return new ChargeStepResult(null, null, Objects.requireNonNull(bankTxId, "bankTxId"), null, null);
    }

    public static ChargeStepResult credited(UUID ledgerRef) {
        return new ChargeStepResult(null, null, null, Objects.requireNonNull(ledgerRef, "ledgerRef"), null);
    }

    public static ChargeStepResult refunded(UUID refundBankTxId) {
        return new ChargeStepResult(null, null, null, null,
                Objects.requireNonNull(refundBankTxId, "refundBankTxId"));
    }

    public static ChargeStepResult failed(ChargeFailureCode failureCode, String lastError) {
        return new ChargeStepResult(failureCode, lastError, null, null, null);
    }
}

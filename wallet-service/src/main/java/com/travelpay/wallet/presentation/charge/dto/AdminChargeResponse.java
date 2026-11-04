package com.travelpay.wallet.presentation.charge.dto;

import com.travelpay.wallet.application.charge.dto.AdminChargeResult;
import com.travelpay.wallet.application.charge.dto.ChargeResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.time.Instant;
import java.util.UUID;

/** ChargeResponse 항목 + userId·attemptCount. 시간은 UTC ISO-8601(Z). 계좌번호는 마스킹 값만 */
public record AdminChargeResponse(UUID chargeId, UUID userId, ChargeStatus status, long amount, Currency currency,
                                  String bankCode, String accountNumberMask,
                                  ChargeFailureCode failureCode, String failureMessage, int attemptCount,
                                  Instant createdAt, Instant updatedAt) {

    public static AdminChargeResponse from(AdminChargeResult result) {
        ChargeResult charge = result.charge();
        return new AdminChargeResponse(charge.chargeId(), result.userId(), charge.status(), charge.amount(),
                charge.currency(), charge.bankCode(), charge.accountNumberMask(), charge.failureCode(),
                charge.failureMessage(), result.attemptCount(), charge.createdAt(), charge.updatedAt());
    }
}

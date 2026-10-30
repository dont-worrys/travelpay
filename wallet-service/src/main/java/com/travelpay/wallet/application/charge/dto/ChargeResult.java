package com.travelpay.wallet.application.charge.dto;

import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.time.Instant;
import java.util.UUID;

/** 계좌번호는 마스킹 값만 담는다. failureMessage는 failureCode의 사용자용 문구 */
public record ChargeResult(UUID chargeId, ChargeStatus status, long amount, Currency currency,
                           String bankCode, String accountNumberMask,
                           ChargeFailureCode failureCode, String failureMessage,
                           Instant createdAt, Instant updatedAt) {
}

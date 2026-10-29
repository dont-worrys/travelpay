package com.travelpay.wallet.application.charge.dto;

import com.travelpay.wallet.domain.Currency;

import java.util.Objects;
import java.util.UUID;

/** idempotencyKey는 헤더 원문(없으면 null). 형식 검사는 서비스가 한다. toString에 계좌번호를 남기지 않는다 */
public record RequestChargeCommand(UUID userId, String idempotencyKey, long amount, Currency currency,
                                   String bankCode, String accountNumber) {

    public RequestChargeCommand {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(bankCode, "bankCode");
        Objects.requireNonNull(accountNumber, "accountNumber");
    }

    @Override
    public String toString() {
        return "RequestChargeCommand[userId=" + userId + ", amount=" + amount + ", currency=" + currency
                + ", bankCode=" + bankCode + "]";
    }
}

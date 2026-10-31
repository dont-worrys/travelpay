package com.travelpay.wallet.application.charge.dto;

import com.travelpay.wallet.domain.Currency;

import java.util.UUID;

/** wallet.charge.requested Outbox payload. 계좌번호·실명은 넣지 않는다 (컨슈머가 chargeId로 충전 행을 다시 읽음) */
public record ChargeRequestedPayload(UUID chargeId, UUID walletId, UUID userId, long amount, Currency currency,
                                     String bankCode, String bankRequestId) {
}

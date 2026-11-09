package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementStatus;

import java.time.Instant;
import java.util.UUID;

public record SettlementResult(
        UUID settlementId,
        UUID merchantId,
        SettlementStatus status,
        Long amount,
        CurrencyCode currency,
        UUID ownerUserId,
        int paymentCount,
        Instant cutoffAt,
        UUID ledgerTransactionId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    public static SettlementResult from(Settlement settlement, int paymentCount) {
        return new SettlementResult(
                settlement.getId(), settlement.getMerchantId(), settlement.getStatus(), settlement.getAmount(),
                settlement.getCurrency(), settlement.getOwnerUserId(), paymentCount, settlement.getCutoffAt(),
                settlement.getLedgerTransactionId(), settlement.getFailureReason(),
                settlement.getCreatedAt(), settlement.getUpdatedAt());
    }
}

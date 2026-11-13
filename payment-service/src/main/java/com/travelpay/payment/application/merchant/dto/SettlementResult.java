package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementStatus;

import java.time.Instant;
import java.util.UUID;

/** 정산. merchantName은 현재 가맹점 이름 */
public record SettlementResult(
        UUID settlementId,
        UUID merchantId,
        String merchantName,
        SettlementStatus status,
        Long amount,
        CurrencyCode currency,
        UUID ownerUserId,
        int paymentCount,
        Instant cutoffAt,
        UUID ledgerTransactionId,
        String failureReason,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static SettlementResult from(Settlement settlement, String merchantName, int paymentCount) {
        return new SettlementResult(
                settlement.getId(), settlement.getMerchantId(), merchantName, settlement.getStatus(),
                settlement.getAmount(),
                settlement.getCurrency(), settlement.getOwnerUserId(), paymentCount, settlement.getCutoffAt(),
                settlement.getLedgerTransactionId(), settlement.getFailureReason(), settlement.getPaidAt(),
                settlement.getCreatedAt(), settlement.getUpdatedAt());
    }
}

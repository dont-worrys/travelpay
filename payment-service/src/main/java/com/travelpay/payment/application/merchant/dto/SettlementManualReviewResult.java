package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.time.Instant;
import java.util.UUID;

/** 정산 MANUAL_REVIEW 목록 한 줄 */
public record SettlementManualReviewResult(UUID settlementId, UUID merchantId, UUID ownerUserId, Long amount,
                                           CurrencyCode currency, SettlementReviewReason reviewReason,
                                           int attemptCount, Instant createdAt, Instant updatedAt) {

    public static SettlementManualReviewResult from(Settlement settlement) {
        return new SettlementManualReviewResult(settlement.getId(), settlement.getMerchantId(),
                settlement.getOwnerUserId(), settlement.getAmount(), settlement.getCurrency(),
                settlement.getReviewReason(), settlement.getAttemptCount(),
                settlement.getCreatedAt(), settlement.getUpdatedAt());
    }
}

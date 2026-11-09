package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.merchant.SettlementReviewResolution;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.time.Instant;
import java.util.UUID;

/** 정산 운영자 처리 기록 한 줄 */
public record SettlementResolutionResult(UUID resolutionId, UUID settlementId, SettlementReviewReason reviewReason,
                                         ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId,
                                         SettlementStatus toStatus, UUID actorId, String description, String memo,
                                         Instant createdAt) {

    public static SettlementResolutionResult from(SettlementReviewResolution resolution) {
        return new SettlementResolutionResult(resolution.getId(), resolution.getSettlementId(),
                resolution.getReviewReason(), resolution.getLedgerStatus(), resolution.getLedgerTransactionId(),
                resolution.getToStatus(), resolution.getActorId(), resolution.getDescription(), resolution.getMemo(),
                resolution.getCreatedAt());
    }
}

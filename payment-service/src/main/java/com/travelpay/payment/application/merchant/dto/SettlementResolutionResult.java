package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.merchant.SettlementReviewResolution;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import com.travelpay.payment.domain.payment.ReviewActorType;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.time.Instant;
import java.util.UUID;

/** 정산 처리 기록 한 줄(운영자 처리·자동 처리) */
public record SettlementResolutionResult(UUID resolutionId, UUID settlementId, SettlementReviewReason reviewReason,
                                         ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId,
                                         SettlementStatus toStatus, ReviewActorType actorType, UUID actorId,
                                         String description, String memo, Instant createdAt) {

    public static SettlementResolutionResult from(SettlementReviewResolution resolution) {
        return new SettlementResolutionResult(resolution.getId(), resolution.getSettlementId(),
                resolution.getReviewReason(), resolution.getLedgerStatus(), resolution.getLedgerTransactionId(),
                resolution.getToStatus(), resolution.getActorType(), resolution.getActorId(), resolution.getDescription(), resolution.getMemo(),
                resolution.getCreatedAt());
    }
}

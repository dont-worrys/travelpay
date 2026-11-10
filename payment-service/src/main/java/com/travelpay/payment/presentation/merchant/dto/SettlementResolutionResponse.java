package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementResolutionResult;

import java.time.Instant;
import java.util.UUID;

/** 정산 운영자 처리 기록. actorId는 처리한 MASTER, description은 시스템 처리 설명 */
public record SettlementResolutionResponse(
        UUID resolutionId,
        UUID settlementId,
        String reviewReason,
        String ledgerStatus,
        UUID ledgerTransactionId,
        String toStatus,
        UUID actorId,
        String description,
        String memo,
        Instant createdAt
) {
    public static SettlementResolutionResponse from(SettlementResolutionResult result) {
        return new SettlementResolutionResponse(
                result.resolutionId(), result.settlementId(), result.reviewReason().name(),
                result.ledgerStatus().name(), result.ledgerTransactionId(), result.toStatus().name(),
                result.actorId(), result.description(), result.memo(), result.createdAt());
    }
}

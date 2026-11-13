package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementResolutionResult;

import java.time.Instant;
import java.util.UUID;

/**
 * 정산 처리 기록. actorType은 OPERATOR(운영자)·SYSTEM(자동 처리), actorId는 처리한 MASTER(자동 처리면 없음),
 * description은 시스템 처리 설명
 */
public record SettlementResolutionResponse(
        UUID resolutionId,
        UUID settlementId,
        String reviewReason,
        String ledgerStatus,
        UUID ledgerTransactionId,
        String toStatus,
        String actorType,
        UUID actorId,
        String description,
        String memo,
        Instant createdAt
) {
    public static SettlementResolutionResponse from(SettlementResolutionResult result) {
        return new SettlementResolutionResponse(
                result.resolutionId(), result.settlementId(), result.reviewReason().name(),
                result.ledgerStatus().name(), result.ledgerTransactionId(), result.toStatus().name(),
                result.actorType().name(), result.actorId(), result.description(), result.memo(), result.createdAt());
    }
}

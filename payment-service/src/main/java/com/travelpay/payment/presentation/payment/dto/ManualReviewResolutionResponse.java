package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.ManualReviewResolutionResult;

import java.time.Instant;
import java.util.UUID;

/**
 * 처리 기록. refundId가 있으면 환불 처리, actorType은 OPERATOR(운영자)·SYSTEM(자동 처리),
 * actorId는 처리한 MASTER(자동 처리면 없음), description은 시스템 처리 설명
 */
public record ManualReviewResolutionResponse(
        UUID resolutionId,
        UUID paymentId,
        UUID refundId,
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
    public static ManualReviewResolutionResponse from(ManualReviewResolutionResult result) {
        return new ManualReviewResolutionResponse(
                result.resolutionId(), result.paymentId(), result.refundId(), result.reviewReason().name(),
                result.ledgerStatus().name(), result.ledgerTransactionId(), result.toStatus(),
                result.actorType().name(), result.actorId(),
                result.description(), result.memo(), result.createdAt());
    }
}

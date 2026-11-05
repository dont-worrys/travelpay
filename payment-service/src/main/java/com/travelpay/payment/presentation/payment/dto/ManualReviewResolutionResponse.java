package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.ManualReviewResolutionResult;

import java.time.Instant;
import java.util.UUID;

/** 운영자 처리 기록. refundId가 있으면 환불 처리, actorId는 처리한 MASTER, description은 시스템 처리 설명 */
public record ManualReviewResolutionResponse(
        UUID resolutionId,
        UUID paymentId,
        UUID refundId,
        String reviewReason,
        String ledgerStatus,
        UUID ledgerTransactionId,
        String toStatus,
        UUID actorId,
        String description,
        String memo,
        Instant createdAt
) {
    public static ManualReviewResolutionResponse from(ManualReviewResolutionResult result) {
        return new ManualReviewResolutionResponse(
                result.resolutionId(), result.paymentId(), result.refundId(), result.reviewReason().name(),
                result.ledgerStatus().name(), result.ledgerTransactionId(), result.toStatus(), result.actorId(),
                result.description(), result.memo(), result.createdAt());
    }
}

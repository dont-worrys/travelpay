package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.PaymentReviewResolution;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.time.Instant;
import java.util.UUID;

/** 운영자 처리 기록 한 줄. refundId가 있으면 환불 처리, toStatus는 처리 대상의 바뀐 상태 */
public record ManualReviewResolutionResult(UUID resolutionId, UUID paymentId, UUID refundId,
                                           SettlementReviewReason reviewReason, ReviewLedgerStatus ledgerStatus,
                                           UUID ledgerTransactionId, String toStatus, UUID actorId,
                                           String description, String memo, Instant createdAt) {
    public static ManualReviewResolutionResult from(PaymentReviewResolution resolution) {
        return new ManualReviewResolutionResult(resolution.getId(), resolution.getPaymentId(),
                resolution.getRefundId(), resolution.getReviewReason(), resolution.getLedgerStatus(),
                resolution.getLedgerTransactionId(), resolution.getToStatus(), resolution.getActorId(),
                resolution.getDescription(), resolution.getMemo(), resolution.getCreatedAt());
    }
}

package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.ManualReviewResolveResult;

import java.util.UUID;

/**
 * status: 처리 대상(결제 또는 환불)의 바뀐 상태, paymentStatus: 처리 후 결제 상태(결제 처리면 status와 같음).
 * ledgerTransactionId는 원장에 기록이 없으면(ledgerStatus NOT_FOUND) 없습니다.
 */
public record ManualReviewResolveResponse(
        UUID paymentId,
        UUID refundId,
        String status,
        String paymentStatus,
        String ledgerStatus,
        UUID ledgerTransactionId
) {
    public static ManualReviewResolveResponse from(ManualReviewResolveResult result) {
        return new ManualReviewResolveResponse(
                result.paymentId(), result.refundId(), result.toStatus(), result.paymentStatus().name(),
                result.ledgerStatus().name(), result.ledgerTransactionId());
    }
}

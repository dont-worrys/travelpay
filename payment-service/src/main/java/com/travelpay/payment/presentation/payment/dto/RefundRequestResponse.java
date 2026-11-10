package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.RefundRequestSummary;

import java.time.Instant;

/** 가장 최근 고객 환불 신청. result: REQUESTED(처리 대기), CANCELLED(고객 취소), REJECTED(거절), ACCEPTED(환불 실행) */
public record RefundRequestResponse(
        String result,
        Instant requestedAt,
        String requestReason,
        Instant processedAt,
        String rejectReason
) {
    public static RefundRequestResponse from(RefundRequestSummary summary) {
        if (summary == null) {
            return null;
        }
        return new RefundRequestResponse(summary.result().name(), summary.requestedAt(), summary.requestReason(),
                summary.processedAt(), summary.rejectReason());
    }
}

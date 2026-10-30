package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundStatus;

import java.time.Instant;
import java.util.UUID;

public record RefundResult(
        UUID refundId,
        RefundStatus status,
        Long amount,
        CurrencyCode currency,
        String reason,
        String declineReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundResult from(Refund refund) {
        return new RefundResult(
                refund.getId(), refund.getStatus(), refund.getAmount(), refund.getCurrency(),
                refund.getReason(), refund.getDeclineReason(),
                refund.getCreatedAt(), refund.getUpdatedAt());
    }
}

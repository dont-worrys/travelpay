package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record RefundResult(
        UUID refundId,
        RefundStatus status,
        Long amount,
        CurrencyCode currency,
        String reason,
        String declineReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static RefundResult from(Refund refund) {
        return new RefundResult(
                refund.getId(), refund.getStatus(), refund.getAmount(), refund.getCurrency(),
                refund.getReason(), refund.getDeclineReason(),
                toKst(refund.getCreatedAt()), toKst(refund.getUpdatedAt()));
    }

    private static OffsetDateTime toKst(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).withZoneSameInstant(KST).toOffsetDateTime();
    }
}

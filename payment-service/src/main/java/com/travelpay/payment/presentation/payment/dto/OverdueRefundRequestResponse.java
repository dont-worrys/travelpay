package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.OverdueRefundRequestResult;

import java.time.Instant;
import java.util.UUID;

public record OverdueRefundRequestResponse(
        UUID paymentId,
        UUID userId,
        UUID merchantId,
        Long amount,
        String currency,
        Instant requestedAt,
        String requestReason
) {
    public static OverdueRefundRequestResponse from(OverdueRefundRequestResult result) {
        return new OverdueRefundRequestResponse(
                result.paymentId(), result.userId(), result.merchantId(), result.amount(),
                result.currency().name(), result.requestedAt(), result.requestReason());
    }
}

package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.PaymentResult;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse (
    UUID paymentId,
    String status,
    Long amount,
    String currency,
    String merchantName,
    String paymentInfo,
    String declineReason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static PaymentResponse from(PaymentResult result) {
        return new PaymentResponse(
                result.paymentId(), result.status().name(), result.amount(), result.currency().name(),
                result.merchantName(), result.paymentInfo(), result.declineReason(),
                result.createdAt(), result.updatedAt());
    }
}

package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.PaymentListResult;

import java.time.Instant;
import java.util.UUID;

public record PaymentListResponse(
        UUID paymentId,
        String status,
        Long amount,
        String currency,
        String merchantName,
        Instant createdAt
) {
    public static PaymentListResponse from(PaymentListResult result) {
        return new PaymentListResponse(
                result.paymentId(), result.status().name(), result.amount(), result.currency().name(),
                result.merchantName(), result.createdAt());
    }
}

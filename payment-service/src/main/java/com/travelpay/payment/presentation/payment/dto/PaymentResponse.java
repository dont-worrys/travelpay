package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.PaymentResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResponse (
    UUID paymentId,
    String status,
    Long amount,
    String currency,
    String merchantName,
    String paymentInfo,
    String declineReason,
    List<RefundResponse> refunds,
    Instant createdAt,
    Instant updatedAt
) {
    public static PaymentResponse from(PaymentResult result) {
        return new PaymentResponse(
                result.paymentId(), result.status().name(), result.amount(), result.currency().name(),
                result.merchantName(), result.paymentInfo(), result.declineReason(),
                result.refunds().stream().map(RefundResponse::from).toList(),
                result.createdAt(), result.updatedAt());
    }
}

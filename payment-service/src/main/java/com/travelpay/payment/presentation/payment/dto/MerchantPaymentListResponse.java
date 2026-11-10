package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.MerchantPaymentListResult;

import java.time.Instant;
import java.util.UUID;

public record MerchantPaymentListResponse(
        UUID paymentId,
        String status,
        Long amount,
        Long refundedAmount,
        String currency,
        String paymentInfo,
        Instant createdAt
) {
    public static MerchantPaymentListResponse from(MerchantPaymentListResult result) {
        return new MerchantPaymentListResponse(
                result.paymentId(), result.status().name(), result.amount(), result.refundedAmount(),
                result.currency().name(), result.paymentInfo(), result.createdAt());
    }
}

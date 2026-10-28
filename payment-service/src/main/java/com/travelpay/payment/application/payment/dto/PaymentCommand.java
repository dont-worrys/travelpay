package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record PaymentCommand(
        UUID userId,
        String idempotencyKey,
        UUID merchantId,
        CurrencyCode currency,
        Long amount,
        String paymentInfo
) {
}

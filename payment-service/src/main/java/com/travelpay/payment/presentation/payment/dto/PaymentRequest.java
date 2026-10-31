package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.domain.payment.CurrencyCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PaymentRequest(
        @NotNull UUID merchantId,
        @NotNull CurrencyCode currency,
        @NotNull @Positive Long amount,
        @Size(max = 255) String paymentInfo
) {
    public PaymentCommand toCommand(UUID userId, String idempotencyKey) {
        return new PaymentCommand(userId, idempotencyKey, merchantId, currency, amount, paymentInfo);
    }
}

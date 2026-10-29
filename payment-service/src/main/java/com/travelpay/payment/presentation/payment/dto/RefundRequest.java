package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.dto.RefundCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RefundRequest(
        @NotBlank @Size(max = 255) String reason
) {
    public RefundCommand toCommand(UUID userId, UUID paymentId, String idempotencyKey) {
        return new RefundCommand(userId, paymentId, idempotencyKey, reason);
    }
}

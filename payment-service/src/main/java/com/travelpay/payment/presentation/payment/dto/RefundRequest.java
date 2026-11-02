package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RefundRequest(
        @NotNull @Positive Long amount,
        @NotBlank @Size(max = 255) String reason
) {
    public RefundCommand toCommand(AuthenticatedUser user, UUID paymentId, String idempotencyKey) {
        return new RefundCommand(user.userId(), user.role(), paymentId, idempotencyKey, amount, reason);
    }
}

package com.travelpay.payment.application.payment.dto;

import com.travelpay.common.auth.AuthRole;

import java.util.UUID;

public record RefundCommand(
        UUID userId,
        AuthRole role,
        UUID paymentId,
        String idempotencyKey,
        Long amount,
        String reason
) {
}

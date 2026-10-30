package com.travelpay.payment.application.payment.dto;

import java.util.UUID;

public record RefundCommand(
        UUID userId,
        UUID paymentId,
        String idempotencyKey,
        String reason
) {
}

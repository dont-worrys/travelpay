package com.travelpay.payment.presentation.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundRejectRequest(
        @NotBlank
        @Size(max = 255)
        String reason
) {
}

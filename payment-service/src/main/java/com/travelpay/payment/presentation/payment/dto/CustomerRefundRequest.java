package com.travelpay.payment.presentation.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerRefundRequest(
        @Size(max = 255)
        @NotBlank
        String reason
) {
}

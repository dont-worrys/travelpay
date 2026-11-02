package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.CreateMerchantCommand;
import com.travelpay.payment.domain.payment.CurrencyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateMerchantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull CurrencyCode currency,
        @NotNull UUID ownerUserId
) {
    public CreateMerchantCommand toCommand() {
        return new CreateMerchantCommand(name, currency, ownerUserId);
    }
}

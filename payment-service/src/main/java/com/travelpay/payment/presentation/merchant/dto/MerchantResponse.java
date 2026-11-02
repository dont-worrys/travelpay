package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.MerchantResult;

import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(
        UUID merchantId,
        String name,
        String currency,
        UUID ownerUserId,
        String status,
        Instant createdAt
) {
    public static MerchantResponse from(MerchantResult result) {
        return new MerchantResponse(result.merchantId(), result.name(), result.currency().name(),
                result.ownerUserId(), result.status().name(), result.createdAt());
    }
}

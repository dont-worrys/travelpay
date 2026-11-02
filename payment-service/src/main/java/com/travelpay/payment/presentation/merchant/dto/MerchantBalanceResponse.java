package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.MerchantBalanceResult;

import java.util.UUID;

public record MerchantBalanceResponse(
        UUID merchantId,
        String currency,
        long balance
) {
    public static MerchantBalanceResponse from(MerchantBalanceResult result) {
        return new MerchantBalanceResponse(result.merchantId(), result.currency().name(), result.balance());
    }
}

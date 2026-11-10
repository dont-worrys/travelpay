package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record MerchantBalanceResult(
        UUID merchantId,
        CurrencyCode currency,
        long balance
) {
}

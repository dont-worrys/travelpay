package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantStatus;

import java.time.Instant;
import java.util.UUID;

public record MerchantResult(
        UUID merchantId,
        String name,
        CurrencyCode currency,
        UUID ownerUserId,
        MerchantStatus status,
        UUID ledgerWalletId,
        Instant createdAt
) {
    public static MerchantResult from(Merchant merchant) {
        return new MerchantResult(merchant.getId(), merchant.getName(), merchant.getCurrency(),
                merchant.getOwnerUserId(), merchant.getStatus(), merchant.getLedgerWalletId(),
                merchant.getCreatedAt());
    }
}

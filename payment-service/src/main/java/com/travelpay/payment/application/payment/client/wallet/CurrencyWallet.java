package com.travelpay.payment.application.payment.client.wallet;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public record CurrencyWallet(
    UUID walletCurrencyId,
    UUID ledgerAccountId,
    CurrencyCode currency
) { }

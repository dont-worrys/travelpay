package com.travelpay.payment.application.payment.client.wallet;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.Optional;
import java.util.UUID;

public interface WalletClient {
    Optional<CurrencyWallet> findCurrencyWallet(UUID userId, CurrencyCode currency);
}

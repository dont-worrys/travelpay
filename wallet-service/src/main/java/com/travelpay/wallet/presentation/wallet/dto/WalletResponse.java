package com.travelpay.wallet.presentation.wallet.dto;

import com.travelpay.wallet.application.wallet.dto.WalletResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.wallet.WalletStatus;

import java.util.List;
import java.util.UUID;

public record WalletResponse(UUID walletId, WalletStatus status, List<Currency> currencies) {

    public static WalletResponse from(WalletResult result) {
        return new WalletResponse(result.walletId(), result.status(), result.currencies());
    }
}

package com.travelpay.wallet.presentation.wallet.dto;

import com.travelpay.wallet.application.wallet.dto.OpenWalletResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.wallet.WalletStatus;

import java.util.List;
import java.util.UUID;

/** 원장 계정 ID는 내부 식별자라 내보내지 않는다 */
public record OpenWalletResponse(UUID walletId, WalletStatus status, List<Currency> currencies) {

    public static OpenWalletResponse from(OpenWalletResult result) {
        return new OpenWalletResponse(result.walletId(), result.status(), result.currencies());
    }
}

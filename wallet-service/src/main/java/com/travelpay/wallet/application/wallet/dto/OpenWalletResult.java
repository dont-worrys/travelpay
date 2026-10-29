package com.travelpay.wallet.application.wallet.dto;

import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.wallet.WalletStatus;

import java.util.List;
import java.util.UUID;

/** activatedNow: 이번 요청에서 PENDING을 ACTIVE로 바꿨는지. 통화 목록은 통화 순(KRW, JPY, CNY) */
public record OpenWalletResult(boolean activatedNow, UUID walletId, WalletStatus status, List<Currency> currencies) {

    public OpenWalletResult {
        currencies = List.copyOf(currencies);
    }
}

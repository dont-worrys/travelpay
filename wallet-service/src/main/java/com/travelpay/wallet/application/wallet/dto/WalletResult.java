package com.travelpay.wallet.application.wallet.dto;

import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.wallet.WalletStatus;

import java.util.List;
import java.util.UUID;

/** 통화 목록은 통화 순(KRW, JPY, CNY). PENDING 지갑은 비어 있을 수 있다 */
public record WalletResult(UUID walletId, WalletStatus status, List<Currency> currencies) {

    public WalletResult {
        currencies = List.copyOf(currencies);
    }
}

package com.travelpay.wallet.application.wallet.dto;

import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.wallet.WalletStatus;

import java.util.List;
import java.util.UUID;

/** 잔액은 통화 최소 단위 정수. 목록은 지갑에 행이 있는 통화만, 통화 순(KRW, JPY, CNY) */
public record InternalWalletBalancesResult(UUID walletId, WalletStatus status, List<Balance> balances) {

    public InternalWalletBalancesResult {
        balances = List.copyOf(balances);
    }

    public record Balance(Currency currency, long balance, WalletStatus status) {
    }
}

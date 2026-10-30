package com.travelpay.wallet.application.wallet.dto;

import com.travelpay.wallet.domain.Currency;

import java.util.List;

/** 잔액은 통화 최소 단위 정수. 목록은 통화 순(KRW, JPY, CNY) */
public record WalletBalancesResult(List<Balance> balances) {

    public WalletBalancesResult {
        balances = List.copyOf(balances);
    }

    public record Balance(Currency currency, long balance) {
    }
}

package com.travelpay.wallet.presentation.wallet.dto;

import com.travelpay.wallet.application.wallet.dto.WalletBalancesResult;
import com.travelpay.wallet.domain.Currency;

import java.util.List;

/** 잔액은 통화 최소 단위 정수 */
public record WalletBalanceResponse(Currency currency, long balance) {

    public static List<WalletBalanceResponse> listOf(WalletBalancesResult result) {
        return result.balances().stream()
                .map(balance -> new WalletBalanceResponse(balance.currency(), balance.balance()))
                .toList();
    }
}

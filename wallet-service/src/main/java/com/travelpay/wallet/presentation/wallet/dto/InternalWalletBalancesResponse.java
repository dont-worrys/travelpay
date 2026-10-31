package com.travelpay.wallet.presentation.wallet.dto;

import com.travelpay.wallet.application.wallet.dto.InternalWalletBalancesResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.wallet.WalletStatus;

import java.util.List;
import java.util.UUID;

/** 환전 파트와 합의한 계약. 필드 이름을 바꾸지 않는다 */
public record InternalWalletBalancesResponse(UUID walletId, WalletStatus status, List<Balance> balances) {

    public static InternalWalletBalancesResponse from(InternalWalletBalancesResult result) {
        return new InternalWalletBalancesResponse(result.walletId(), result.status(), result.balances().stream()
                .map(balance -> new Balance(balance.currency(), balance.balance(), balance.status()))
                .toList());
    }

    public record Balance(
            Currency currency,
            /** 통화별 최소 단위 정수 (CNY는 1/100 위안) */
            long balance,
            WalletStatus status) {
    }
}

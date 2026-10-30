package com.travelpay.ledger.presentation.account.dto;

import com.travelpay.ledger.application.account.dto.WalletAccountsResult;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountStatus;

import java.util.List;
import java.util.UUID;

public record WalletAccountResponse(UUID accountId, Currency currency, long balance, AccountStatus status) {

    public static WalletAccountResponse from(WalletAccountsResult.Account account) {
        return new WalletAccountResponse(account.accountId(), account.currency(),
                account.balance(), account.status());
    }

    public static List<WalletAccountResponse> listOf(WalletAccountsResult result) {
        return result.accounts().stream().map(WalletAccountResponse::from).toList();
    }
}

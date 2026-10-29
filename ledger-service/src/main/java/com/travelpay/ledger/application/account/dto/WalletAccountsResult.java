package com.travelpay.ledger.application.account.dto;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountStatus;
import com.travelpay.ledger.domain.account.LedgerAccount;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** created: 이번 요청으로 새 계정이 하나라도 생겼는지. 계정 목록은 통화 순(KRW, JPY, CNY) */
public record WalletAccountsResult(boolean created, List<Account> accounts) {

    public WalletAccountsResult {
        accounts = List.copyOf(accounts);
    }

    public static WalletAccountsResult of(boolean created, List<LedgerAccount> accounts) {
        return new WalletAccountsResult(created, accounts.stream()
                .map(Account::from)
                .sorted(Comparator.comparing(Account::currency))
                .toList());
    }

    public record Account(UUID accountId, Currency currency, long balance, AccountStatus status) {

        static Account from(LedgerAccount account) {
            return new Account(account.getId(), account.getCurrency(),
                    account.getBalance(), account.getStatus());
        }
    }
}

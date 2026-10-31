package com.travelpay.exchange.domain.wallet;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;

/**
 * 환전에 쓰는 지갑 잔액입니다. 지갑 서비스의 상태 문자열은 infrastructure에서 ACTIVE 여부로 바꿔 둡니다.
 *
 * @param walletId 지갑 ID. 환전 신청 때 원장 계정을 지정하는 데 씁니다.
 * @param active   지갑이 ACTIVE인지
 * @param accounts 통화별 계정. 지갑에 없는 통화는 들어 있지 않습니다.
 */
public record WalletBalances(UUID walletId, boolean active, Map<Currency, Account> accounts) {

    /** 계정 맵의 키와 잔액 통화가 같아야 합니다. 맵은 복사해 바뀌지 않게 둡니다. */
    public WalletBalances {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(accounts, "accounts");
        accounts.forEach((currency, account) -> {
            if (account.balance().currency() != currency) {
                throw new IllegalArgumentException("balance currency must be " + currency);
            }
        });
        accounts = Map.copyOf(accounts);
    }

    public Optional<Account> account(Currency currency) {
        return Optional.ofNullable(accounts.get(currency));
    }

    /** 지갑과 그 통화 계정이 모두 ACTIVE여야 환전으로 돈을 뺄 수 있습니다. */
    public boolean canSpend(Currency currency) {
        return isUsable(currency);
    }

    /** 지갑과 그 통화 계정이 모두 ACTIVE여야 환전으로 돈을 받을 수 있습니다. 원장도 같은 기준으로 거절합니다. */
    public boolean canReceive(Currency currency) {
        return isUsable(currency);
    }

    private boolean isUsable(Currency currency) {
        return active && account(currency).map(Account::active).orElse(false);
    }

    /**
     * 지갑 안의 통화 하나의 계정입니다.
     *
     * @param balance 잔액
     * @param active  계정이 ACTIVE인지
     */
    public record Account(Money balance, boolean active) {

        public Account {
            Objects.requireNonNull(balance, "balance");
        }
    }
}

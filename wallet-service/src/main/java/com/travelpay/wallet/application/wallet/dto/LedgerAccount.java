package com.travelpay.wallet.application.wallet.dto;

import com.travelpay.wallet.domain.Currency;

import java.util.Objects;
import java.util.UUID;

/** 원장의 지갑 계정 하나 (통화별) */
public record LedgerAccount(Currency currency, UUID accountId, long balance) {

    public LedgerAccount {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(accountId, "accountId");
    }
}

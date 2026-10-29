package com.travelpay.ledger.application.account.dto;

import java.util.Objects;
import java.util.UUID;

public record OpenWalletAccountsCommand(UUID walletId) {

    public OpenWalletAccountsCommand {
        Objects.requireNonNull(walletId, "walletId");
    }
}

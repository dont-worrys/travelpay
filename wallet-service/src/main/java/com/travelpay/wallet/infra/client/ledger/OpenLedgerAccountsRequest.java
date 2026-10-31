package com.travelpay.wallet.infra.client.ledger;

import java.util.Objects;
import java.util.UUID;

public record OpenLedgerAccountsRequest(UUID walletId) {

    public OpenLedgerAccountsRequest {
        Objects.requireNonNull(walletId, "walletId");
    }
}

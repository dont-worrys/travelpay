package com.travelpay.payment.application.payment.client.wallet;

import java.util.UUID;

public record PaymentWallet(UUID walletId, String status) {

    private static final String ACTIVE = "ACTIVE";

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}

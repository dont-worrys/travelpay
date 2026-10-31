package com.travelpay.wallet.application.wallet.dto;

import java.util.Objects;
import java.util.UUID;

public record OpenWalletCommand(UUID userId) {

    public OpenWalletCommand {
        Objects.requireNonNull(userId, "userId");
    }
}

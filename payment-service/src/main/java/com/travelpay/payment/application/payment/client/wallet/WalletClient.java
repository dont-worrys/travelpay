package com.travelpay.payment.application.payment.client.wallet;

import java.util.Optional;
import java.util.UUID;

public interface WalletClient {
    Optional<Wallet> findWallet(UUID userId);
}

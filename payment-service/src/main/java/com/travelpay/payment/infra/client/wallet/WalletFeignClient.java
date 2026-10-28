package com.travelpay.payment.infra.client.wallet;

import com.travelpay.payment.application.payment.client.wallet.CurrencyWallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import com.travelpay.payment.domain.payment.CurrencyCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@Profile("!prod")
public class WalletFeignClient implements WalletClient {
    @Override
    public Optional<CurrencyWallet> findCurrencyWallet(UUID userId, CurrencyCode currency) {
        UUID walletCurrencyId = UUID.nameUUIDFromBytes(("wallet:" + userId + ":" + currency).getBytes());
        UUID ledgerAccountId = UUID.nameUUIDFromBytes(("ledger:" + userId + ":" + currency).getBytes());
        return Optional.of(new CurrencyWallet(walletCurrencyId, ledgerAccountId, currency));
    }
}

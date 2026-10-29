package com.travelpay.wallet.infra.persistence.wallet;

import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.wallet.WalletCurrency;
import com.travelpay.wallet.domain.wallet.WalletCurrencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WalletCurrencyRepositoryImpl implements WalletCurrencyRepository {

    private final WalletCurrencyJpaRepository jpaRepository;

    @Override
    public void addIfAbsent(UUID walletId, Currency currency, UUID ledgerAccountId) {
        jpaRepository.insertIfAbsent(UUID.randomUUID(), walletId, currency.name(), ledgerAccountId);
    }

    @Override
    public List<WalletCurrency> findByWalletId(UUID walletId) {
        return jpaRepository.findAllByWalletId(walletId);
    }
}

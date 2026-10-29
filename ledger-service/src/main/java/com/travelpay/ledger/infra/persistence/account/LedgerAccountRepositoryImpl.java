package com.travelpay.ledger.infra.persistence.account;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.LedgerAccount;
import com.travelpay.ledger.domain.account.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LedgerAccountRepositoryImpl implements LedgerAccountRepository {

    private final LedgerAccountJpaRepository jpaRepository;

    @Override
    public boolean addWalletAccountIfAbsent(UUID walletId, Currency currency) {
        return jpaRepository.insertWalletAccountIfAbsent(UUID.randomUUID(), walletId, currency.name()) == 1;
    }

    @Override
    public List<LedgerAccount> findWalletAccounts(UUID walletId) {
        return jpaRepository.findAllByWalletIdAndAccountType(walletId, AccountType.WALLET);
    }
}

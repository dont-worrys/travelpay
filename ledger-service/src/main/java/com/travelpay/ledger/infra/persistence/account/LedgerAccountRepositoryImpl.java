package com.travelpay.ledger.infra.persistence.account;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.LedgerAccount;
import com.travelpay.ledger.domain.account.LedgerAccountRepository;
import com.travelpay.ledger.domain.account.OwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    @Override
    public Optional<UUID> findUserWalletAccountId(UUID walletId, Currency currency) {
        return jpaRepository.findUserAccountId(OwnerType.USER, walletId, AccountType.WALLET, currency);
    }

    @Override
    public Optional<UUID> findSystemAccountId(AccountType accountType, Currency currency) {
        return jpaRepository.findSystemAccountId(OwnerType.SYSTEM, accountType, currency);
    }

    @Override
    public List<LedgerAccount> lockAllInIdOrder(Collection<UUID> accountIds) {
        return jpaRepository.lockAllByIdInOrderById(accountIds);
    }
}

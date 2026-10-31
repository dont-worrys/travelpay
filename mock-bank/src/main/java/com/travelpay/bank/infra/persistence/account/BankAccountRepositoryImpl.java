package com.travelpay.bank.infra.persistence.account;

import com.travelpay.bank.domain.account.BankAccount;
import com.travelpay.bank.domain.account.BankAccountOwnerType;
import com.travelpay.bank.domain.account.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class BankAccountRepositoryImpl implements BankAccountRepository {

    private final BankAccountJpaRepository bankAccountJpaRepository;

    @Override
    public Optional<BankAccount> findByBankCodeAndAccountNumber(String bankCode, String accountNumber) {
        return bankAccountJpaRepository.findByBankCodeAndAccountNumber(bankCode, accountNumber);
    }

    @Override
    public Optional<BankAccount> findPlatformAccount() {
        return bankAccountJpaRepository.findFirstByOwnerType(BankAccountOwnerType.PLATFORM);
    }

    @Override
    public int decreaseBalance(UUID accountId, long amount) {
        return bankAccountJpaRepository.decreaseBalance(accountId, amount, Instant.now());
    }

    @Override
    public int increaseBalance(UUID accountId, long amount) {
        return bankAccountJpaRepository.increaseBalance(accountId, amount, Instant.now());
    }
}

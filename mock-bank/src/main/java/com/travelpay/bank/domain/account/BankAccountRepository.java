package com.travelpay.bank.domain.account;

import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository {

    Optional<BankAccount> findByBankCodeAndAccountNumber(String bankCode, String accountNumber);

    Optional<BankAccount> findPlatformAccount();

    /** 잔액이 충분할 때만 차감한다. 반영된 행 수(0 또는 1)를 돌려준다. */
    int decreaseBalance(UUID accountId, long amount);

    int increaseBalance(UUID accountId, long amount);
}

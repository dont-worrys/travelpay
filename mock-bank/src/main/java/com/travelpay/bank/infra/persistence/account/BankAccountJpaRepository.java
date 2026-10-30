package com.travelpay.bank.infra.persistence.account;

import com.travelpay.bank.domain.account.BankAccount;
import com.travelpay.bank.domain.account.BankAccountOwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountJpaRepository extends JpaRepository<BankAccount, UUID> {

    Optional<BankAccount> findByBankCodeAndAccountNumber(String bankCode, String accountNumber);

    Optional<BankAccount> findFirstByOwnerType(BankAccountOwnerType ownerType);

    @Modifying
    @Query("""
            update BankAccount a
               set a.balance = a.balance - :amount, a.updatedAt = :now
             where a.id = :id and a.balance >= :amount
            """)
    int decreaseBalance(@Param("id") UUID id, @Param("amount") long amount, @Param("now") Instant now);

    @Modifying
    @Query("""
            update BankAccount a
               set a.balance = a.balance + :amount, a.updatedAt = :now
             where a.id = :id
            """)
    int increaseBalance(@Param("id") UUID id, @Param("amount") long amount, @Param("now") Instant now);
}

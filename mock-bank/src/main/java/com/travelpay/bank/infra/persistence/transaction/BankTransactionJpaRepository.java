package com.travelpay.bank.infra.persistence.transaction;

import com.travelpay.bank.domain.transaction.BankTransaction;
import com.travelpay.bank.domain.transaction.BankTransactionStatus;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BankTransactionJpaRepository extends JpaRepository<BankTransaction, UUID> {

    Optional<BankTransaction> findByRequestId(String requestId);

    boolean existsByOriginalTxIdAndTypeAndStatus(UUID originalTxId, BankTransactionType type,
                                                 BankTransactionStatus status);
}

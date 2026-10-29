package com.travelpay.ledger.infra.persistence.transaction;

import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionJpaRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByReferenceTypeAndReferenceId(ReferenceType referenceType, UUID referenceId);
}

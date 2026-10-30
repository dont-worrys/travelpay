package com.travelpay.ledger.domain.transaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository {

    Optional<LedgerTransaction> findByReference(ReferenceType referenceType, UUID referenceId);

    boolean existsById(UUID transactionId);

    /** 즉시 반영(flush)한다. 같은 reference가 이미 있으면 DuplicateReferenceException */
    LedgerTransaction save(LedgerTransaction transaction);

    void saveEntries(List<LedgerEntry> entries);
}

package com.travelpay.ledger.infra.persistence.transaction;

import com.travelpay.ledger.domain.transaction.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntry, UUID> {
}

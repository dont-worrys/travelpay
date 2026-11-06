package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationResultJpaRepository extends JpaRepository<ReconciliationResult, UUID> {
}

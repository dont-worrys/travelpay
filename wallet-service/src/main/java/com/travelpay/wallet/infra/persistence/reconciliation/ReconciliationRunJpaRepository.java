package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationRunJpaRepository extends JpaRepository<ReconciliationRun, UUID> {
}

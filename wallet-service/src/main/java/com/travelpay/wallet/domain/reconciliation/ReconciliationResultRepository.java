package com.travelpay.wallet.domain.reconciliation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ReconciliationResultRepository {

    void saveAll(List<ReconciliationResult> results);

    /** mismatchType이 null이면 전체 */
    Page<ReconciliationResult> findByRun(UUID runId, MismatchType mismatchType, Pageable pageable);
}

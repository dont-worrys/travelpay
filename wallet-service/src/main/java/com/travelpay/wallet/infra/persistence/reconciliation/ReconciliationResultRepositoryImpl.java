package com.travelpay.wallet.infra.persistence.reconciliation;

import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ReconciliationResultRepositoryImpl implements ReconciliationResultRepository {

    private final ReconciliationResultJpaRepository jpaRepository;

    @Override
    public void saveAll(List<ReconciliationResult> results) {
        jpaRepository.saveAll(results);
    }
}

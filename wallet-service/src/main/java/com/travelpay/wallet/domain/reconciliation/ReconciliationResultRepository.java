package com.travelpay.wallet.domain.reconciliation;

import java.util.List;

public interface ReconciliationResultRepository {

    void saveAll(List<ReconciliationResult> results);
}

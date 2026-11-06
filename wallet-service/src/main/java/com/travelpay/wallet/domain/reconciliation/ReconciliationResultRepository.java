package com.travelpay.wallet.domain.reconciliation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationResultRepository {

    void saveAll(List<ReconciliationResult> results);

    /** mismatchType이 null이면 전체 */
    Page<ReconciliationResult> findByRun(UUID runId, MismatchType mismatchType, Pageable pageable);

    Optional<ReconciliationResult> findById(UUID resultId);

    /**
     * 같은 충전의 PENDING 결과일 때만 RESOLVED로 바꾸고 보정 ID를 연결한다 (mismatch_type·snapshot은 그대로).
     * 바뀐 행 수(0 또는 1). 트랜잭션 안에서만 사용
     */
    int markResolved(UUID resultId, UUID chargeId, UUID resolutionId);
}

package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResultRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 대사의 DB 트랜잭션 단계. 은행·원장 호출이 트랜잭션 안에 들어가지 않도록
 * ChargeReconciliationService(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationTransactionSteps {

    static final int TX_TIMEOUT_SECONDS = 3;
    /** 충전 후보 읽기·결과 저장은 건수에 비례하므로 길게 */
    static final int BULK_TX_TIMEOUT_SECONDS = 60;

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationResultRepository resultRepository;
    private final ChargeRequestRepository chargeRequestRepository;

    /** RUNNING 실행을 만든다. 이미 RUNNING이 있으면 ReconciliationAlreadyRunningException */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public UUID start(Instant from, Instant to) {
        return runRepository.saveNew(ReconciliationRun.start(from, to, Instant.now())).getId();
    }

    /** 결과 없이 FAILED로 끝낸다 */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public ReconciliationRunResult fail(UUID runId, String reason) {
        ReconciliationRun run = load(runId);
        run.fail(reason, Instant.now());
        return ReconciliationRunResult.from(run);
    }

    /**
     * 대사 대상 충전: created_at이 [from, to)인 충전 + 은행 목록 requestId·원장 목록 referenceId가 가리키는 충전.
     * id로 중복을 없앤다 (상태 무관, 처리 중 제외는 호출자가 한다)
     */
    @Transactional(readOnly = true, timeout = BULK_TX_TIMEOUT_SECONDS)
    public List<ChargeRequest> loadCharges(Instant from, Instant to, Collection<String> bankRequestIds,
                                           Collection<UUID> ledgerReferenceIds) {
        Map<UUID, ChargeRequest> charges = new LinkedHashMap<>();
        chargeRequestRepository.findCreatedBetween(from, to).forEach(c -> charges.put(c.getId(), c));
        chargeRequestRepository.findByBankOrRefundRequestIds(bankRequestIds).forEach(c -> charges.put(c.getId(), c));
        chargeRequestRepository.findAllByIds(ledgerReferenceIds).forEach(c -> charges.put(c.getId(), c));
        return List.copyOf(charges.values());
    }

    /** 결과 저장과 실행 종료(COMPLETED·PARTIAL)를 한 트랜잭션으로 */
    @Transactional(timeout = BULK_TX_TIMEOUT_SECONDS)
    public ReconciliationRunResult complete(UUID runId, List<ReconciliationResult> results, int targetCount,
                                            int matchedCount, int mismatchCount, int unverifiableCount) {
        ReconciliationRun run = load(runId);
        resultRepository.saveAll(results);
        run.finish(targetCount, matchedCount, mismatchCount, unverifiableCount, Instant.now());
        return ReconciliationRunResult.from(run);
    }

    private ReconciliationRun load(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("대사 실행을 찾을 수 없습니다: " + runId));
    }
}

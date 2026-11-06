package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResultRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunNotRunningException;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 대사의 DB 트랜잭션 단계. 은행·원장 호출이 트랜잭션 안에 들어가지 않도록
 * ChargeReconciliationService(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다.
 * 실행 종료(완료·실패)는 status = RUNNING 조건부 UPDATE라, 도중에 오래된 RUNNING으로 정리된 실행을 덮어쓰지 않는다.
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
    public UUID start(Instant from, Instant to, Instant overlapFrom) {
        return runRepository.saveNew(ReconciliationRun.start(from, to, overlapFrom, Instant.now())).getId();
    }

    /** 결과 없이 FAILED로 끝낸다. 이미 RUNNING이 아니면 바꾸지 않고 현재 상태를 돌려준다 */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public ReconciliationRunResult fail(UUID runId, String reason) {
        runRepository.failIfRunning(runId, reason, Instant.now());
        return ReconciliationRunResult.from(load(runId));
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

    /**
     * 결과 저장과 실행 종료(COMPLETED·PARTIAL)를 한 트랜잭션으로.
     * 실행이 이미 RUNNING이 아니면 ReconciliationRunNotRunningException으로 트랜잭션 전체를 되돌린다 (결과도 저장 안 됨)
     */
    @Transactional(timeout = BULK_TX_TIMEOUT_SECONDS)
    public ReconciliationRunResult complete(UUID runId, List<ReconciliationResult> results, int targetCount,
                                            int matchedCount, int mismatchCount, int unverifiableCount) {
        resultRepository.saveAll(results);
        int updated = runRepository.finishIfRunning(runId, ReconciliationRun.finishedStatus(unverifiableCount),
                targetCount, matchedCount, mismatchCount, unverifiableCount, Instant.now());
        if (updated == 0) {
            throw new ReconciliationRunNotRunningException(runId);
        }
        return ReconciliationRunResult.from(load(runId));
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public ReconciliationRunResult current(UUID runId) {
        return ReconciliationRunResult.from(load(runId));
    }

    /** started_at이 threshold보다 앞선 RUNNING을 FAILED로 정리한다. 바뀐 행 수 */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public int failStaleRunning(Instant threshold, String reason) {
        return runRepository.failStaleRunning(threshold, reason, Instant.now());
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public boolean existsRunning() {
        return runRepository.existsRunning();
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ReconciliationRun> findLatestClosed() {
        return runRepository.findLatestClosed();
    }

    private ReconciliationRun load(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("대사 실행을 찾을 수 없습니다: " + runId));
    }
}

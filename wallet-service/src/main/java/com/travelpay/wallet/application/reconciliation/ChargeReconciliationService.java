package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.ChargeReconciliationJudge.Finding;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ChargeFacts;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.reconciliation.MismatchType;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunNotRunningException;
import com.travelpay.wallet.domain.reconciliation.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 충전 대사 실행 1회: 기간 [from, to)의 은행 거래·충전·원장 거래를 식별자로 대조해 어긋남을 저장한다.
 * 찾고 기록만 하며 충전 상태나 돈은 바꾸지 않는다. 외부 호출이 트랜잭션 안에 들어가지 않도록 트랜잭션 없이 동작한다.
 * <p>
 * 매칭: 충전 bank_request_id·refund_request_id = 은행 requestId, 충전 id = 원장 referenceId.
 * 은행·원장 기간 목록은 끝까지 한 번에 받아 키로 맵을 만들고, 상태상 있어야 하는 기록이 목록에 없을 때만 단건 재조회한다
 * (COMPLETED: 출금·원장, REFUNDED: 출금·환불). 없어야 하는 기록은 목록에 없으면 없음으로 본다.
 * 기간 밖에 찍힌 기록은 그 기간의 실행에서 목록 키로 충전을 찾아 판정 후보에 넣으므로 놓치지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeReconciliationService {

    private static final Set<ChargeStatus> IN_PROGRESS = Set.of(ChargeStatus.REQUESTED, ChargeStatus.BANK_PROCESSING,
            ChargeStatus.CREDIT_PROCESSING, ChargeStatus.REFUND_PROCESSING);

    private final ReconciliationTransactionSteps steps;
    private final BankTransactionQueryClient bankClient;
    private final LedgerTransactionQueryClient ledgerClient;
    private final ReconciliationSnapshots snapshots;

    /**
     * RUNNING 실행이 이미 있으면 ReconciliationAlreadyRunningException (실행 기록을 만들지 않음).
     * 기간 목록을 끝까지 받지 못하면 FAILED(실패 사유만 저장). 예상하지 못한 예외도 FAILED로 끝내고 다시 던진다.
     */
    public ReconciliationRunResult run(Instant from, Instant to) {
        return run(from, to, null);
    }

    /** overlapFrom: 이전 기간과 겹쳐 다시 보는 시작 시각 (계산한 기간만 기록, 지정 기간이면 null) */
    public ReconciliationRunResult run(Instant from, Instant to, Instant overlapFrom) {
        UUID runId = steps.start(from, to, overlapFrom);
        try {
            return reconcile(runId, from, to);
        } catch (RuntimeException | Error e) {
            failQuietly(runId, e);
            throw e;
        }
    }

    private ReconciliationRunResult reconcile(UUID runId, Instant from, Instant to) {
        List<BankTransactionRecord> bankList;
        List<LedgerTransactionRecord> ledgerList;
        try {
            bankList = bankClient.listTransactions(from, to);
            ledgerList = ledgerClient.listChargeTransactions(from, to);
        } catch (ReconciliationSourceException e) {
            log.warn("충전 대사 기간 목록 수집 실패 runId={} from={} to={} reason={}", runId, from, to, e.getMessage());
            return steps.fail(runId, e.getMessage());
        }

        Map<String, BankTransactionRecord> bankByRequestId = new LinkedHashMap<>();
        bankList.forEach(r -> bankByRequestId.put(r.requestId(), r));
        Map<UUID, LedgerTransactionRecord> ledgerByReference = new LinkedHashMap<>();
        ledgerList.forEach(r -> ledgerByReference.put(r.referenceId(), r));

        List<ChargeRequest> charges = steps.loadCharges(from, to, bankByRequestId.keySet(),
                ledgerByReference.keySet());

        Instant detectedAt = Instant.now();
        List<ReconciliationResult> results = new ArrayList<>();
        int target = 0;
        int matched = 0;
        int mismatched = 0;
        int unverifiable = 0;
        for (ChargeRequest charge : charges) {
            if (IN_PROGRESS.contains(charge.getStatus())) {
                continue;
            }
            target++;
            ChargeFacts facts = facts(charge, bankByRequestId, ledgerByReference);
            List<Finding> findings = ChargeReconciliationJudge.judge(charge, facts);
            if (findings.isEmpty()) {
                matched++;
                continue;
            }
            boolean hasUnavailable = findings.stream().anyMatch(f -> f.type() == MismatchType.SOURCE_UNAVAILABLE);
            boolean hasMismatch = findings.stream().anyMatch(f -> f.type() != MismatchType.SOURCE_UNAVAILABLE);
            unverifiable += hasUnavailable ? 1 : 0;
            mismatched += hasMismatch ? 1 : 0;
            String snapshot = snapshots.ofCharge(charge, facts);
            for (Finding finding : findings) {
                results.add(ReconciliationResult.ofCharge(runId, charge.getId(), finding.type(), finding.detail(),
                        snapshot, detectedAt));
            }
        }

        List<ReconciliationResult> orphans = orphans(runId, charges, bankByRequestId, ledgerByReference, detectedAt);
        results.addAll(orphans);
        mismatched += orphans.size();

        ReconciliationRunResult result;
        try {
            result = steps.complete(runId, results, target, matched, mismatched, unverifiable);
        } catch (ReconciliationRunNotRunningException e) {
            // 도중에 오래된 RUNNING으로 정리됨: 결과는 롤백됐고 정리된 상태·사유를 그대로 둔다
            log.warn("충전 대사 실행이 도중에 정리되어 결과를 저장하지 않음 runId={}", runId);
            return steps.current(runId);
        }
        log.info("충전 대사 완료 runId={} status={} target={} matched={} mismatch={} unverifiable={}",
                runId, result.status(), target, matched, mismatched, unverifiable);
        return result;
    }

    /** 상태별로 필요한 소스만 단건 재조회한다. MANUAL_REVIEW·FAILED는 목록 사실만 쓴다 */
    private ChargeFacts facts(ChargeRequest charge, Map<String, BankTransactionRecord> bankByRequestId,
                              Map<UUID, LedgerTransactionRecord> ledgerByReference) {
        boolean completed = charge.getStatus() == ChargeStatus.COMPLETED;
        boolean refunded = charge.getStatus() == ChargeStatus.REFUNDED;
        String debitKey = charge.getBankRequestId();
        String refundKey = charge.getRefundRequestId();
        UUID ledgerKey = charge.getId();
        return new ChargeFacts(
                lookup(bankByRequestId.get(debitKey), completed || refunded,
                        () -> bankClient.findTransaction(debitKey)),
                lookup(bankByRequestId.get(refundKey), refunded,
                        () -> bankClient.findTransaction(refundKey)),
                lookup(ledgerByReference.get(ledgerKey), completed,
                        () -> ledgerClient.findChargeTransaction(ledgerKey)));
    }

    private static <T> SourceLookup<T> lookup(T fromList, boolean expected, Supplier<SourceLookup<T>> single) {
        if (fromList != null) {
            return SourceLookup.found(fromList);
        }
        return expected ? single.get() : SourceLookup.notFound();
    }

    /** 은행 목록에서 어떤 충전의 출금·환불 요청에도 없는 거래, 원장 목록에서 충전이 없는 거래 */
    private List<ReconciliationResult> orphans(UUID runId, List<ChargeRequest> charges,
                                               Map<String, BankTransactionRecord> bankByRequestId,
                                               Map<UUID, LedgerTransactionRecord> ledgerByReference,
                                               Instant detectedAt) {
        Set<String> ownedRequestIds = new HashSet<>();
        Set<UUID> chargeIds = new HashSet<>();
        for (ChargeRequest charge : charges) {
            ownedRequestIds.add(charge.getBankRequestId());
            ownedRequestIds.add(charge.getRefundRequestId());
            chargeIds.add(charge.getId());
        }
        List<ReconciliationResult> orphans = new ArrayList<>();
        bankByRequestId.forEach((requestId, record) -> {
            if (!ownedRequestIds.contains(requestId)) {
                orphans.add(ReconciliationResult.ofOrphan(runId, SourceType.BANK, requestId, MismatchType.BANK_ORPHAN,
                        "은행 거래(" + record.type() + " " + record.status() + ")에 대응하는 충전 없음",
                        snapshots.ofBankOrphan(record), detectedAt));
            }
        });
        ledgerByReference.forEach((referenceId, record) -> {
            if (!chargeIds.contains(referenceId)) {
                orphans.add(ReconciliationResult.ofOrphan(runId, SourceType.LEDGER,
                        record.transactionId().toString(), MismatchType.LEDGER_ORPHAN,
                        "원장 거래(" + record.status() + ")의 referenceId " + referenceId + "인 충전 없음",
                        snapshots.ofLedgerOrphan(record), detectedAt));
            }
        });
        return orphans;
    }

    /** 예외 메시지는 SQL·응답이 섞일 수 있어 클래스명만 남긴다 */
    private void failQuietly(UUID runId, Throwable cause) {
        log.error("충전 대사 처리 중 오류 runId={} exception={}", runId, cause.getClass().getName());
        try {
            steps.fail(runId, "대사 처리 중 오류: " + cause.getClass().getName());
        } catch (RuntimeException e) {
            log.error("충전 대사 실행을 FAILED로 끝내지 못함 runId={} exception={}", runId, e.getClass().getName());
        }
    }
}

package com.travelpay.wallet.application.reconciliation;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationResultItem;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunSummary;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.reconciliation.MismatchType;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResultRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 관리자 대사 조회. 페이지 규칙은 관리자 충전 목록과 같다:
 * page는 0부터, size는 1 이상이며 최대 크기를 넘으면 최대 크기로 조회, offset이 int 범위를 넘으면 400
 */
@Service
@RequiredArgsConstructor
public class ReconciliationQueryService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final Sort RUN_SORT = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));
    private static final Sort RESULT_SORT = Sort.by(Sort.Order.desc("detectedAt"), Sort.Order.desc("id"));

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationResultRepository resultRepository;

    /** status가 null이면 전체. started_at·id 내림차순 */
    @Transactional(readOnly = true, timeout = ReconciliationTransactionSteps.TX_TIMEOUT_SECONDS)
    public Page<ReconciliationRunSummary> listRuns(ReconciliationRunStatus status, int page, int size) {
        return runRepository.findAll(status, pageable(page, size, RUN_SORT)).map(ReconciliationRunSummary::from);
    }

    /** 실행이 없으면 404 */
    @Transactional(readOnly = true, timeout = ReconciliationTransactionSteps.TX_TIMEOUT_SECONDS)
    public ReconciliationRunSummary getRun(UUID runId) {
        return runRepository.findById(runId).map(ReconciliationRunSummary::from)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.RECONCILIATION_RUN_NOT_FOUND));
    }

    /** 실행이 없으면 404. mismatchType이 null이면 전체. detected_at·id 내림차순 */
    @Transactional(readOnly = true, timeout = ReconciliationTransactionSteps.TX_TIMEOUT_SECONDS)
    public Page<ReconciliationResultItem> listResults(UUID runId, MismatchType mismatchType, int page, int size) {
        Pageable pageable = pageable(page, size, RESULT_SORT);
        if (runRepository.findById(runId).isEmpty()) {
            throw new BusinessException(WalletErrorCode.RECONCILIATION_RUN_NOT_FOUND);
        }
        return resultRepository.findByRun(runId, mismatchType, pageable).map(ReconciliationResultItem::from);
    }

    private static Pageable pageable(int page, int size, Sort sort) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), sort);
        // offset이 int를 넘으면 JPA firstResult(int)에서 넘친다
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return pageable;
    }
}

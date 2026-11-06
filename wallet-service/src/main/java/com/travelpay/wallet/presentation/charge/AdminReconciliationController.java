package com.travelpay.wallet.presentation.charge;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.reconciliation.ReconciliationQueryService;
import com.travelpay.wallet.application.reconciliation.ReconciliationRunner;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationResultItem;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunSummary;
import com.travelpay.wallet.domain.reconciliation.MismatchType;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;
import com.travelpay.wallet.presentation.charge.dto.AdminReconciliationDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자(MASTER) 충전 대사 실행·조회. Gateway의 /api/v1/admin/charges/** 라우트 아래에 둔다.
 * 수동 실행은 동기로 끝까지 대사한 뒤 응답한다. 기간은 [from, to), 마이크로초보다 세밀한 시각은 다음 마이크로초로 올린다
 */
@RestController
@RequestMapping("/api/v1/admin/charges/reconciliations")
@RequiredArgsConstructor
public class AdminReconciliationController {

    private final ReconciliationRunner runner;
    private final ReconciliationQueryService queryService;

    /** from·to 둘 다 생략하면 스케줄러와 같은 기간, 둘 다 지정하면 그 기간 재대사 */
    @PostMapping
    public ApiResponse<ReconciliationRunSummary> run(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requireMaster(user);
        ReconciliationRunResult result = runner.runManual(from, to);
        return ApiResponse.success(queryService.getRun(result.runId()));
    }

    /** status 생략 시 전체. started_at·id 내림차순 */
    @GetMapping
    public ApiResponse<PageResponse<ReconciliationRunSummary>> listRuns(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) ReconciliationRunStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMaster(user);
        return ApiResponse.success(PageResponse.from(queryService.listRuns(status, page, size)));
    }

    /** 실행 요약 + 결과 목록. mismatchType 생략 시 전체, detected_at·id 내림차순 */
    @GetMapping("/{runId}")
    public ApiResponse<AdminReconciliationDetailResponse> getRun(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID runId,
            @RequestParam(required = false) MismatchType mismatchType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMaster(user);
        PageResponse<ReconciliationResultItem> results =
                PageResponse.from(queryService.listResults(runId, mismatchType, page, size));
        return ApiResponse.success(new AdminReconciliationDetailResponse(queryService.getRun(runId), results));
    }

    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}

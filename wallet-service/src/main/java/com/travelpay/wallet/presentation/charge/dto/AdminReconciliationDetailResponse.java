package com.travelpay.wallet.presentation.charge.dto;

import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationResultItem;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunSummary;

/** 대사 실행 상세: 실행 요약 + 어긋남 결과 페이지 */
public record AdminReconciliationDetailResponse(ReconciliationRunSummary run,
                                                PageResponse<ReconciliationResultItem> results) {
}

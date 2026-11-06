package com.travelpay.wallet.application.reconciliation.dto;

import com.travelpay.wallet.domain.reconciliation.MismatchType;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ResolutionStatus;
import com.travelpay.wallet.domain.reconciliation.SourceType;

import java.time.Instant;
import java.util.UUID;

/** 관리자 조회용 어긋남 1건. snapshot은 저장된 JSON 문자열 */
public record ReconciliationResultItem(UUID resultId, UUID chargeId, SourceType sourceType, String sourceKey,
                                       MismatchType mismatchType, String detail, String snapshot,
                                       Instant detectedAt, ResolutionStatus resolutionStatus) {

    public static ReconciliationResultItem from(ReconciliationResult result) {
        return new ReconciliationResultItem(result.getId(), result.getChargeId(), result.getSourceType(),
                result.getSourceKey(), result.getMismatchType(), result.getDetail(), result.getSnapshot(),
                result.getDetectedAt(), result.getResolutionStatus());
    }
}

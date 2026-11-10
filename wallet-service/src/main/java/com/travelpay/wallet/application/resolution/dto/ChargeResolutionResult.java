package com.travelpay.wallet.application.resolution.dto;

import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.resolution.ChargeResolution;
import com.travelpay.wallet.domain.resolution.ResolutionAction;

import java.time.Instant;
import java.util.UUID;

/** 반영된 보정. 저장된 기록에서만 만들어, 같은 Idempotency-Key 재요청에도 처음 결과와 같다 */
public record ChargeResolutionResult(UUID resolutionId, UUID chargeId, ResolutionAction action,
                                     ChargeStatus resultStatus, UUID operatorId, String reason,
                                     UUID reconciliationResultId, SourceFact withdrawal, SourceFact refund,
                                     SourceFact ledger, Instant resolvedAt) {

    public static ChargeResolutionResult from(ChargeResolution resolution) {
        return new ChargeResolutionResult(resolution.getId(), resolution.getChargeId(), resolution.getAction(),
                resolution.getResultStatus(), resolution.getOperatorId(), resolution.getReason(),
                resolution.getReconciliationResultId(), SourceFact.ofEvidence(resolution.getWithdrawal()),
                SourceFact.ofEvidence(resolution.getRefund()), SourceFact.ofEvidence(resolution.getLedger()),
                resolution.getCreatedAt());
    }
}

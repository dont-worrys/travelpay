package com.travelpay.wallet.application.resolution.dto;

import com.travelpay.wallet.domain.resolution.ResolutionAction;

import java.util.UUID;

/** @param idempotencyKey 헤더 원문 (서비스가 해석한다) */
public record ResolveChargeCommand(UUID chargeId, UUID operatorId, String idempotencyKey, ResolutionAction action,
                                   String reason, UUID reconciliationResultId) {
}

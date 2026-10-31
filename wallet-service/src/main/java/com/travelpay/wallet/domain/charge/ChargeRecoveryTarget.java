package com.travelpay.wallet.domain.charge;

import java.time.Instant;
import java.util.UUID;

/**
 * 복구 대상 충전. updatedAt은 DB에서 읽은 값 그대로이며 선점 조건(updated_at = ?)에 그대로 쓴다
 */
public record ChargeRecoveryTarget(UUID id, ChargeStatus status, Instant updatedAt) {
}

package com.travelpay.wallet.domain.charge;

import java.time.Instant;
import java.util.UUID;

/**
 * 복구 대상 충전. updatedAt은 DB에서 읽은 값 그대로이며 선점 조건(updated_at = ?)에 그대로 쓴다.
 * bankCode는 복구 스케줄러가 선점 전에 이번 주기에 보내지 않을 은행인지 보는 데 쓴다
 */
public record ChargeRecoveryTarget(UUID id, ChargeStatus status, Instant updatedAt, String bankCode) {
}

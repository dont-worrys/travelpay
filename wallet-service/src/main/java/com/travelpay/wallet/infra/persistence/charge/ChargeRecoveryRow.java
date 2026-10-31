package com.travelpay.wallet.infra.persistence.charge;

import java.time.Instant;
import java.util.UUID;

/** 복구 대상 조회 네이티브 쿼리 프로젝션 */
public interface ChargeRecoveryRow {

    UUID getId();

    String getStatus();

    Instant getUpdatedAt();
}

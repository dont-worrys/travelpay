package com.travelpay.wallet.application.reconciliation.dto;

import com.travelpay.wallet.domain.reconciliation.ReconciliationRunType;

import java.time.Instant;

/**
 * 대사 기간 [from, to). overlapFrom은 직전 기간과 겹쳐 다시 보는 시작 시각 (직전 실행이 없거나 지정 기간이면 null).
 * runType: 기간 계산으로 정했으면 AUTO, 지정했으면 MANUAL
 */
public record ReconciliationPeriod(Instant from, Instant to, Instant overlapFrom, ReconciliationRunType runType) {
}

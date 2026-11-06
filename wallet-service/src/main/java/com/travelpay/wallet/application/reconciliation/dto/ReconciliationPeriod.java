package com.travelpay.wallet.application.reconciliation.dto;

import java.time.Instant;

/** 대사 기간 [from, to). overlapFrom은 직전 기간과 겹쳐 다시 보는 시작 시각 (직전 실행이 없거나 지정 기간이면 null) */
public record ReconciliationPeriod(Instant from, Instant to, Instant overlapFrom) {
}

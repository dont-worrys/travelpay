package com.travelpay.wallet.domain.reconciliation;

/**
 * RUNNING: 진행 중 (동시에 하나만, DB 부분 UNIQUE 인덱스로 보장)
 * COMPLETED: 모든 대상 판정, PARTIAL: 일부 충전에 SOURCE_UNAVAILABLE, FAILED: 기간 목록 수집 실패 등으로 결과 없음
 */
public enum ReconciliationRunStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED
}

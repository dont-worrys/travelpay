package com.travelpay.wallet.domain.reconciliation;

/** 어긋남 처리 상태 */
public enum ResolutionStatus {
    PENDING,
    /** 운영자 보정으로 처리됨 (reconciliation_results.resolution_id에 보정 ID) */
    RESOLVED
}

package com.travelpay.wallet.domain.reconciliation;

import java.util.UUID;

/** 종료하려는 실행이 이미 RUNNING이 아님 (실행 도중 오래된 RUNNING으로 정리됨 등). 결과 저장 트랜잭션을 되돌린다 */
public class ReconciliationRunNotRunningException extends RuntimeException {

    public ReconciliationRunNotRunningException(UUID runId) {
        super("대사 실행이 이미 RUNNING이 아닙니다: " + runId);
    }
}

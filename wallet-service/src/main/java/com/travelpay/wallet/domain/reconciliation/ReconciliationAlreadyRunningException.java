package com.travelpay.wallet.domain.reconciliation;

/** RUNNING 실행이 이미 있어 새 실행을 시작하지 않음 */
public class ReconciliationAlreadyRunningException extends RuntimeException {

    public ReconciliationAlreadyRunningException(Throwable cause) {
        super("진행 중인 대사 실행이 있습니다.", cause);
    }
}

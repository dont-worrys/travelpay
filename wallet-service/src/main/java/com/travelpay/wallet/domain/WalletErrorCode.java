package com.travelpay.wallet.domain;

import com.travelpay.common.error.ErrorCode;

/** 지갑 전용 오류 코드. 공통 404(경로 없음)와 구분하기 위해 별도 코드를 쓴다. */
public enum WalletErrorCode implements ErrorCode {

    WALLET_NOT_FOUND("WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", 404),
    WALLET_NOT_ACTIVE("WALLET_NOT_ACTIVE", "사용할 수 없는 지갑 상태입니다.", 409),
    CHARGE_NOT_FOUND("CHARGE_NOT_FOUND", "충전 내역을 찾을 수 없습니다.", 404),
    CHARGE_INVALID_IDEMPOTENCY_KEY("CHARGE_INVALID_IDEMPOTENCY_KEY", "Idempotency-Key 헤더를 확인해 주세요.", 400),
    CHARGE_IDEMPOTENCY_CONFLICT("CHARGE_IDEMPOTENCY_CONFLICT", "같은 Idempotency-Key로 다른 충전을 요청할 수 없습니다.", 409),
    CHARGE_CURRENCY_NOT_SUPPORTED("CHARGE_CURRENCY_NOT_SUPPORTED", "원화(KRW)만 충전할 수 있습니다.", 400),
    CHARGE_USER_NOT_ACTIVE("CHARGE_USER_NOT_ACTIVE", "충전할 수 없는 회원 상태입니다.", 409),
    WALLET_LEDGER_UNAVAILABLE("WALLET_LEDGER_UNAVAILABLE", "잔액 정보를 일시적으로 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503),
    RECONCILIATION_ALREADY_RUNNING("RECONCILIATION_ALREADY_RUNNING", "이미 실행 중인 충전 대사가 있습니다.", 409),
    RECONCILIATION_PERIOD_NOT_READY("RECONCILIATION_PERIOD_NOT_READY", "대사할 닫힌 기간이 아직 없습니다.", 409),
    RECONCILIATION_RUN_NOT_FOUND("RECONCILIATION_RUN_NOT_FOUND", "충전 대사 실행을 찾을 수 없습니다.", 404),
    CHARGE_NOT_IN_MANUAL_REVIEW("CHARGE_NOT_IN_MANUAL_REVIEW", "수동 확인 상태의 충전이 아닙니다. 이미 보정되었을 수 있습니다.", 409),
    CHARGE_RESOLUTION_IDEMPOTENCY_CONFLICT("CHARGE_RESOLUTION_IDEMPOTENCY_CONFLICT", "같은 Idempotency-Key로 다른 보정을 요청할 수 없습니다.", 409),
    CHARGE_RESOLUTION_REJECTED("CHARGE_RESOLUTION_REJECTED", "현재 은행·원장 사실로는 요청한 보정을 할 수 없습니다.", 409),
    CHARGE_RESOLUTION_SOURCE_UNAVAILABLE("CHARGE_RESOLUTION_SOURCE_UNAVAILABLE", "은행·원장 거래를 일시적으로 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503),
    RECONCILIATION_RESULT_NOT_FOR_CHARGE("RECONCILIATION_RESULT_NOT_FOR_CHARGE", "이 충전의 대사 결과가 아닙니다.", 400),
    RECONCILIATION_RESULT_ALREADY_RESOLVED("RECONCILIATION_RESULT_ALREADY_RESOLVED", "이미 처리된 대사 결과입니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    WalletErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public int getStatus() {
        return status;
    }
}

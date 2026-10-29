package com.travelpay.ledger.domain;

import com.travelpay.common.error.ErrorCode;

/** 원장 전용 오류 코드. 공통 404(경로 없음)와 구분하기 위해 별도 코드를 쓴다. */
public enum LedgerErrorCode implements ErrorCode {

    LEDGER_ACCOUNT_NOT_FOUND("LEDGER_ACCOUNT_NOT_FOUND", "원장 계정을 찾을 수 없습니다.", 404);

    private final String code;
    private final String message;
    private final int status;

    LedgerErrorCode(String code, String message, int status) {
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

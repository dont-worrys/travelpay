package com.travelpay.bank.application.transaction;

import com.travelpay.common.error.ErrorCode;

/** 업무 거절(200 + FAILED)이 아닌 요청 자체의 오류 */
public enum BankErrorCode implements ErrorCode {

    REQUEST_ID_CONFLICT("BANK_REQUEST_ID_CONFLICT", "같은 requestId로 다른 유형의 거래가 이미 있습니다.", 409),
    TRANSACTION_NOT_FOUND("BANK_TRANSACTION_NOT_FOUND", "거래를 찾을 수 없습니다.", 404);

    private final String code;
    private final String message;
    private final int status;

    BankErrorCode(String code, String message, int status) {
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

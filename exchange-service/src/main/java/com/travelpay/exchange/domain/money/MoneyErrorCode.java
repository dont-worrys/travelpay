package com.travelpay.exchange.domain.money;

import com.travelpay.common.error.ErrorCode;

public enum MoneyErrorCode implements ErrorCode {

    INVALID_AMOUNT("EXCHANGE_INVALID_AMOUNT", "환전 금액이 올바르지 않습니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    MoneyErrorCode(String code, String message, int status) {
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

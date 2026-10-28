package com.travelpay.exchange.domain.rate;

import com.travelpay.common.error.ErrorCode;

public enum ExchangeRateErrorCode implements ErrorCode {

    UNSUPPORTED_CURRENCY("EXCHANGE_UNSUPPORTED_CURRENCY", "지원하지 않는 통화입니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    ExchangeRateErrorCode(String code, String message, int status) {
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

package com.travelpay.exchange.domain.quote;

import com.travelpay.common.error.ErrorCode;

public enum ExchangeQuoteErrorCode implements ErrorCode {

    UNSUPPORTED_PAIR("EXCHANGE_UNSUPPORTED_PAIR", "원화와 외화 사이의 환전만 지원합니다.", 400),
    RATE_UNAVAILABLE("EXCHANGE_RATE_UNAVAILABLE", "지금은 환율을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503),
    QUOTE_NOT_FOUND("EXCHANGE_QUOTE_NOT_FOUND", "견적을 찾을 수 없습니다.", 404);

    private final String code;
    private final String message;
    private final int status;

    ExchangeQuoteErrorCode(String code, String message, int status) {
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

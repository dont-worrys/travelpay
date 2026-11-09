package com.travelpay.exchange.domain.reservation;

import com.travelpay.common.error.ErrorCode;

public enum ExchangeReservationErrorCode implements ErrorCode {

    INVALID_TARGET_RATE("EXCHANGE_RESERVATION_INVALID_TARGET_RATE",
            "목표 환율은 0보다 큰 값으로, 소수점 둘째 자리까지 입력해 주세요.", 400),
    INVALID_EXPIRY("EXCHANGE_RESERVATION_INVALID_EXPIRY",
            "예약 만료 시각은 현재 시각 이후부터 최대 예약 가능 기간 이내로 설정해 주세요.", 400);

    private final String code;
    private final String message;
    private final int status;

    ExchangeReservationErrorCode(String code, String message, int status) {
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

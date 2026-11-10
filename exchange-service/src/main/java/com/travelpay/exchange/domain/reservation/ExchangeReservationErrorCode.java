package com.travelpay.exchange.domain.reservation;

import com.travelpay.common.error.ErrorCode;

public enum ExchangeReservationErrorCode implements ErrorCode {

    INVALID_TARGET_RATE("EXCHANGE_RESERVATION_INVALID_TARGET_RATE",
            "목표 환율은 0보다 큰 값으로, 소수점 둘째 자리까지 입력해 주세요.", 400),
    INVALID_EXPIRY("EXCHANGE_RESERVATION_INVALID_EXPIRY",
            "예약 만료 시각은 현재 시각 이후부터 최대 예약 가능 기간 이내로 설정해 주세요.", 400),
    RESERVATION_NOT_FOUND("EXCHANGE_RESERVATION_NOT_FOUND", "환전 예약을 찾을 수 없습니다.", 404),
    TOO_MANY_WAITING("EXCHANGE_RESERVATION_TOO_MANY_WAITING",
            "대기 중인 환전 예약이 너무 많습니다. 기존 예약을 취소한 뒤 다시 시도해 주세요.", 409),
    IDEMPOTENCY_KEY_REUSED("EXCHANGE_RESERVATION_IDEMPOTENCY_KEY_REUSED",
            "같은 Idempotency-Key로 다른 환전 예약을 할 수 없습니다.", 409),
    NOT_WAITING("EXCHANGE_RESERVATION_NOT_WAITING",
            "대기 중인 예약만 취소할 수 있습니다. 이미 체결·취소·만료된 예약입니다.", 409);

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

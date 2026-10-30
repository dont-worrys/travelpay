package com.travelpay.payment.transfer.domain;

import com.travelpay.common.error.ErrorCode;

public enum TransferErrorCode implements ErrorCode {

    INVALID_IDEMPOTENCY_KEY("TRANSFER_INVALID_IDEMPOTENCY_KEY", "Idempotency-Key 헤더를 확인해 주세요.", 400),
    SELF_TRANSFER("TRANSFER_SELF_TRANSFER", "본인에게는 송금할 수 없습니다.", 400),
    UNSUPPORTED_CURRENCY("TRANSFER_UNSUPPORTED_CURRENCY", "지원하지 않는 통화입니다.", 400),
    NOT_FOUND("TRANSFER_NOT_FOUND", "송금 내역을 찾을 수 없습니다.", 404),
    RECEIVER_NOT_FOUND("TRANSFER_RECEIVER_NOT_FOUND", "수취인을 찾을 수 없습니다.", 404),
    IDEMPOTENCY_KEY_REUSED("TRANSFER_IDEMPOTENCY_KEY_REUSED", "같은 Idempotency-Key로 다른 송금을 요청할 수 없습니다.", 409),
    SENDER_WALLET_UNAVAILABLE("TRANSFER_SENDER_WALLET_UNAVAILABLE", "송금할 수 있는 지갑이 없습니다.", 422),
    RECEIVER_UNAVAILABLE("TRANSFER_RECEIVER_UNAVAILABLE", "송금할 수 없는 수취인입니다.", 422),
    INSUFFICIENT_BALANCE("TRANSFER_INSUFFICIENT_BALANCE", "잔액이 부족합니다.", 422);

    private final String code;
    private final String message;
    private final int status;

    TransferErrorCode(String code, String message, int status) {
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

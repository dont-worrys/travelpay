package com.travelpay.exchange.domain.order;

import com.travelpay.common.error.ErrorCode;

public enum ExchangeOrderErrorCode implements ErrorCode {

    INVALID_IDEMPOTENCY_KEY("EXCHANGE_INVALID_IDEMPOTENCY_KEY",
            "Idempotency-Key는 공백 없는 ASCII 1~100자로 입력해 주세요.", 400),
    QUOTE_EXPIRED("EXCHANGE_QUOTE_EXPIRED", "견적이 만료되었습니다. 견적을 다시 받아 주세요.", 409),
    QUOTE_ALREADY_USED("EXCHANGE_QUOTE_ALREADY_USED", "이미 환전 신청에 사용된 견적입니다.", 409),
    IDEMPOTENCY_KEY_REUSED("EXCHANGE_IDEMPOTENCY_KEY_REUSED",
            "같은 Idempotency-Key로 다른 환전을 신청할 수 없습니다.", 409),
    ORDER_NOT_FOUND("EXCHANGE_ORDER_NOT_FOUND", "환전 주문을 찾을 수 없습니다.", 404),
    WALLET_NOT_FOUND("EXCHANGE_WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", 404),
    WALLET_NOT_AVAILABLE("EXCHANGE_WALLET_NOT_AVAILABLE", "지금은 이 지갑으로 환전할 수 없습니다.", 409),
    WALLET_SERVICE_UNAVAILABLE("EXCHANGE_WALLET_SERVICE_UNAVAILABLE",
            "지금은 지갑을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503),
    LEDGER_UNAVAILABLE("EXCHANGE_LEDGER_UNAVAILABLE",
            "지금은 환전할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503),
    LEDGER_REJECTED_INSUFFICIENT_BALANCE("EXCHANGE_LEDGER_REJECTED_INSUFFICIENT_BALANCE",
            "잔액이 부족해 환전하지 못했습니다.", 422),
    LEDGER_REJECTED_WALLET_SUSPENDED("EXCHANGE_LEDGER_REJECTED_WALLET_SUSPENDED",
            "사용할 수 없는 지갑 계정이라 환전하지 못했습니다.", 422),
    LEDGER_REJECTED_ACCOUNT_NOT_FOUND("EXCHANGE_LEDGER_REJECTED_ACCOUNT_NOT_FOUND",
            "지갑 계정이 없어 환전하지 못했습니다.", 422),
    NOT_PROCESSED("EXCHANGE_NOT_PROCESSED",
            "처리 시간이 초과되어 취소되었습니다.", 422),
    PROCESSING_ERROR("EXCHANGE_PROCESSING_ERROR",
            "처리 중 오류로 취소되었습니다.", 422),
    NOT_MANUAL_REVIEW("EXCHANGE_NOT_MANUAL_REVIEW",
            "관리자 처리 대상(MANUAL_REVIEW)이 아니거나 이미 처리되었습니다.", 409),
    LEDGER_CORRECTION_REQUIRED("EXCHANGE_LEDGER_CORRECTION_REQUIRED",
            "원장 거래 내용이 주문과 달라 처리할 수 없습니다. 원장 보정이 필요합니다.", 409),
    LEDGER_LOOKUP_UNAVAILABLE("EXCHANGE_LEDGER_LOOKUP_UNAVAILABLE",
            "원장 거래 결과를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503);

    private final String code;
    private final String message;
    private final int status;

    ExchangeOrderErrorCode(String code, String message, int status) {
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

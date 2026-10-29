package com.travelpay.wallet.domain;

import com.travelpay.common.error.ErrorCode;

/** 지갑 전용 오류 코드. 공통 404(경로 없음)와 구분하기 위해 별도 코드를 쓴다. */
public enum WalletErrorCode implements ErrorCode {

    WALLET_NOT_FOUND("WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", 404),
    WALLET_NOT_ACTIVE("WALLET_NOT_ACTIVE", "사용할 수 없는 지갑 상태입니다.", 409);

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

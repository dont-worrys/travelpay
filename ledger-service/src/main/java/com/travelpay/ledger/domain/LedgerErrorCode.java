package com.travelpay.ledger.domain;

import com.travelpay.common.error.ErrorCode;
import com.travelpay.ledger.domain.transaction.RejectReason;

/** 원장 전용 오류 코드. 공통 404(경로 없음)와 구분하기 위해 별도 코드를 쓴다. */
public enum LedgerErrorCode implements ErrorCode {

    LEDGER_ACCOUNT_NOT_FOUND("LEDGER_ACCOUNT_NOT_FOUND", "원장 계정을 찾을 수 없습니다.", 404),
    LEDGER_TRANSACTION_NOT_FOUND("LEDGER_TRANSACTION_NOT_FOUND", "원장 거래를 찾을 수 없습니다.", 404),
    /** 재시도해도 풀리지 않는 요청 오류라 5xx가 아닌 400 */
    LEDGER_ORIGINAL_TRANSACTION_NOT_FOUND("LEDGER_ORIGINAL_TRANSACTION_NOT_FOUND", "원거래를 찾을 수 없습니다.", 400),
    LEDGER_REFERENCE_CONFLICT("LEDGER_REFERENCE_CONFLICT", "같은 참조로 다른 내용의 거래가 이미 있습니다.", 409),
    LEDGER_REJECTED_INSUFFICIENT_BALANCE("LEDGER_REJECTED_INSUFFICIENT_BALANCE", "잔액이 부족해 거래가 거절됐습니다.", 422),
    LEDGER_REJECTED_WALLET_SUSPENDED("LEDGER_REJECTED_WALLET_SUSPENDED", "사용할 수 없는 계정이라 거래가 거절됐습니다.", 422),
    LEDGER_REJECTED_ACCOUNT_NOT_FOUND("LEDGER_REJECTED_ACCOUNT_NOT_FOUND", "계정이 없어 거래가 거절됐습니다.", 422);

    private final String code;
    private final String message;
    private final int status;

    LedgerErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public static LedgerErrorCode rejected(RejectReason reason) {
        return switch (reason) {
            case INSUFFICIENT_BALANCE -> LEDGER_REJECTED_INSUFFICIENT_BALANCE;
            case WALLET_SUSPENDED -> LEDGER_REJECTED_WALLET_SUSPENDED;
            case ACCOUNT_NOT_FOUND -> LEDGER_REJECTED_ACCOUNT_NOT_FOUND;
        };
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

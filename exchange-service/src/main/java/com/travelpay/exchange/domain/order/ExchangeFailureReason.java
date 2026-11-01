package com.travelpay.exchange.domain.order;

import java.util.Optional;

import com.travelpay.common.error.ErrorCode;

/**
 * 환전 주문 실패 사유입니다. 원장 거절 사유와 같은 값을 써서 대사할 때 그대로 비교합니다.
 * 원장은 거절을 422와 error.code {@code LEDGER_REJECTED_<사유>}로 알려 줍니다.
 */
public enum ExchangeFailureReason {
    /** 지갑 잔액이 수수료 포함 금액보다 적음 */
    INSUFFICIENT_BALANCE(ExchangeOrderErrorCode.LEDGER_REJECTED_INSUFFICIENT_BALANCE),
    /** 원장 계정이 정지됨 */
    WALLET_SUSPENDED(ExchangeOrderErrorCode.LEDGER_REJECTED_WALLET_SUSPENDED),
    /** 원장 계정을 찾지 못함 */
    ACCOUNT_NOT_FOUND(ExchangeOrderErrorCode.LEDGER_REJECTED_ACCOUNT_NOT_FOUND);

    private static final String LEDGER_REJECT_PREFIX = "LEDGER_REJECTED_";

    private final ExchangeOrderErrorCode errorCode;

    ExchangeFailureReason(ExchangeOrderErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    /** 원장 거절 코드에 해당하는 사유입니다. 원장이 나중에 추가한, 우리가 모르는 사유면 empty입니다. */
    public static Optional<ExchangeFailureReason> fromLedgerRejectCode(String rejectCode) {
        if (rejectCode == null || !rejectCode.startsWith(LEDGER_REJECT_PREFIX)) {
            return Optional.empty();
        }
        String name = rejectCode.substring(LEDGER_REJECT_PREFIX.length());
        for (ExchangeFailureReason reason : values()) {
            if (reason.name().equals(name)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }

    /** 실패한 주문에 응답할 오류 코드(422)입니다. 같은 멱등 키 재요청에도 같은 코드를 돌려줍니다. */
    public ErrorCode errorCode() {
        return errorCode;
    }
}

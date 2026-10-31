package com.travelpay.wallet.domain.charge;

/** displayMessage: 충전 조회 응답의 failureMessage로 쓰는 사용자용 문구 (last_error는 응답에 넣지 않음) */
public enum ChargeFailureCode {
    BANK_INSUFFICIENT_BALANCE("은행 계좌 잔액이 부족합니다."),
    BANK_ACCOUNT_SUSPENDED("거래가 정지된 은행 계좌입니다."),
    BANK_ACCOUNT_NOT_FOUND("은행 계좌를 찾을 수 없습니다."),
    BANK_HOLDER_MISMATCH("은행 계좌의 예금주가 회원 실명과 일치하지 않습니다."),
    LEDGER_REJECTED("충전을 처리하지 못했습니다. 고객센터에 문의해 주세요."),
    RETRY_EXHAUSTED("충전을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final String displayMessage;

    ChargeFailureCode(String displayMessage) {
        this.displayMessage = displayMessage;
    }

    public String displayMessage() {
        return displayMessage;
    }
}

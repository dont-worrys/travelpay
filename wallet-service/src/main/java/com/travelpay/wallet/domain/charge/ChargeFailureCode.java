package com.travelpay.wallet.domain.charge;

/** displayMessage: 충전 조회 응답의 failureMessage로 쓰는 사용자용 문구 (last_error는 응답에 넣지 않음) */
public enum ChargeFailureCode {
    BANK_INSUFFICIENT_BALANCE("은행 계좌 잔액이 부족합니다."),
    BANK_ACCOUNT_SUSPENDED("거래가 정지된 은행 계좌입니다."),
    BANK_ACCOUNT_NOT_FOUND("은행 계좌를 찾을 수 없습니다."),
    BANK_HOLDER_MISMATCH("은행 계좌의 예금주가 회원 실명과 일치하지 않습니다."),
    /** 위 코드로 분류되지 않는 은행 출금 거절. 은행이 보낸 원래 코드는 last_error에 남긴다 */
    BANK_REJECTED("은행에서 출금을 거절했습니다."),
    LEDGER_REJECTED("충전을 완료하지 못해 출금된 금액을 환불합니다. 처리 상태는 충전 내역에서 확인해 주세요."),
    RETRY_EXHAUSTED("충전 처리 결과를 확인하고 있습니다. 같은 금액을 다시 충전하지 말고 잠시 후 상태를 확인해 주세요."),
    /** 은행·원장 응답이 409·400·형식 이상이라 재시도로 풀리지 않음. 원인은 last_error에 남긴다 */
    RESPONSE_INVALID("충전 처리 결과를 확인하고 있습니다. 같은 금액을 다시 충전하지 말고 잠시 후 상태를 확인해 주세요.");

    private final String displayMessage;

    ChargeFailureCode(String displayMessage) {
        this.displayMessage = displayMessage;
    }

    public String displayMessage() {
        return displayMessage;
    }
}

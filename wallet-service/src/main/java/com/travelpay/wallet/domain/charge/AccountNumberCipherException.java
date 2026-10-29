package com.travelpay.wallet.domain.charge;

/**
 * 계좌번호 암호화·복호화 실패. 구현체는 로그를 남기지 않고 이 예외만 던진다.
 * 로그는 chargeId를 아는 호출자가 남긴다. 공통 처리기가 500으로 응답한다.
 */
public class AccountNumberCipherException extends IllegalStateException {

    public AccountNumberCipherException(String message) {
        super(message);
    }

    public AccountNumberCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}

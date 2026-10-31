package com.travelpay.user.common.error;

import com.travelpay.common.error.ErrorCode;

/** User의 일반 회원가입·로그인과 내부 회원 조회에서 사용하는 업무 오류 계약입니다. */
public enum UserErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXISTS("USER_EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", 409),
    PHONE_ALREADY_EXISTS("USER_PHONE_ALREADY_EXISTS", "이미 사용 중인 휴대폰 번호입니다.", 409),
    TERMS_CONSENT_INVALID("USER_TERMS_CONSENT_INVALID", "필수 약관 동의와 적용 버전을 확인해 주세요.", 400),
    INVALID_CREDENTIALS("USER_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.", 401),
    ACCOUNT_SUSPENDED("USER_ACCOUNT_SUSPENDED", "이용이 정지된 계정입니다.", 403),
    ACCOUNT_CLOSED("USER_ACCOUNT_CLOSED", "이용이 종료된 계정입니다.", 403),
    RECIPIENT_NOT_FOUND("USER_RECIPIENT_NOT_FOUND", "수취인을 찾을 수 없습니다.", 404),
    NOT_FOUND("USER_NOT_FOUND", "회원을 찾을 수 없습니다.", 404);

    private final String code;
    private final String message;
    private final int status;

    UserErrorCode(String code, String message, int status) {
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

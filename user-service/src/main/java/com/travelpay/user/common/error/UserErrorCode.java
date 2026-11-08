package com.travelpay.user.common.error;

import com.travelpay.common.error.ErrorCode;

/** User의 회원가입·인증, 내부 회원 조회, 관리자 회원·약관 관리에서 사용하는 업무 오류 계약입니다. */
public enum UserErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXISTS("USER_EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", 409),
    PHONE_ALREADY_EXISTS("USER_PHONE_ALREADY_EXISTS", "이미 사용 중인 휴대폰 번호입니다.", 409),
    TERMS_CONSENT_INVALID("USER_TERMS_CONSENT_INVALID", "필수 약관 동의와 적용 버전을 확인해 주세요.", 400),
    INVALID_CREDENTIALS("USER_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.", 401),
    CURRENT_PASSWORD_MISMATCH("USER_CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다.", 400),
    NEW_PASSWORD_SAME_AS_CURRENT("USER_NEW_PASSWORD_SAME_AS_CURRENT", "새 비밀번호는 현재 비밀번호와 다르게 입력해 주세요.", 400),
    CREDENTIAL_CONFLICT("USER_CREDENTIAL_CONFLICT", "로그인 정보가 변경되었습니다. 다시 로그인한 후 시도해 주세요.", 409),
    ACCOUNT_UPDATED_SESSION_UNAVAILABLE("USER_ACCOUNT_UPDATED_SESSION_UNAVAILABLE",
            "계정 정보는 변경되었지만 로그인 상태를 갱신하지 못했습니다. 다시 로그인해 주세요.", 503),
    ACCOUNT_SUSPENDED("USER_ACCOUNT_SUSPENDED", "이용이 정지된 계정입니다.", 403),
    ACCOUNT_CLOSED("USER_ACCOUNT_CLOSED", "이용이 종료된 계정입니다.", 403),
    RECIPIENT_NOT_FOUND("USER_RECIPIENT_NOT_FOUND", "수취인을 찾을 수 없습니다.", 404),
    NOT_FOUND("USER_NOT_FOUND", "회원을 찾을 수 없습니다.", 404),
    MASTER_PROTECTION_CONFLICT("USER_MASTER_PROTECTION_CONFLICT", "자기 계정은 정지할 수 없습니다.", 409),
    CLOSED_ACCOUNT_UNCHANGEABLE("USER_CLOSED_ACCOUNT_UNCHANGEABLE", "이용이 종료된 계정의 상태는 바꿀 수 없습니다.", 409),
    TERMS_VERSION_CONFLICT("USER_TERMS_VERSION_CONFLICT", "같은 약관 버전이나 같은 시행 시각의 약관이 이미 있습니다.", 409),
    TERMS_EFFECTIVE_AT_NOT_FUTURE("USER_TERMS_EFFECTIVE_AT_NOT_FUTURE", "시행 시각은 등록 시점보다 1분 이상 뒤로 지정해 주세요.", 400);

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

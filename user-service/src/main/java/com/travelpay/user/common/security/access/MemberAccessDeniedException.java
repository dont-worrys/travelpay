package com.travelpay.user.common.security.access;

import java.io.Serial;

import com.travelpay.user.common.error.UserErrorCode;
import org.springframework.security.access.AccessDeniedException;

/** 회원 상태에 따른 거절을 Spring Security의 403 처리로 전달합니다. */
final class MemberAccessDeniedException extends AccessDeniedException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UserErrorCode errorCode;

    MemberAccessDeniedException(UserErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    UserErrorCode getErrorCode() {
        return errorCode;
    }
}

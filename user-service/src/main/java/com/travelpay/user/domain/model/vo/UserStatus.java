package com.travelpay.user.domain.model.vo;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;

/** 회원의 서비스 이용 상태입니다. */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public void requireActive() {
        var error = switch (this) {
            case ACTIVE -> null;
            case SUSPENDED -> UserErrorCode.ACCOUNT_SUSPENDED;
            case CLOSED -> UserErrorCode.ACCOUNT_CLOSED;
        };
        if (error != null) {
            throw new BusinessException(error);
        }
    }
}

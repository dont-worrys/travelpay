package com.travelpay.user.presentation.dto;

import java.util.UUID;

import com.travelpay.user.application.dto.SignupResult;
import com.travelpay.user.domain.model.vo.UserStatus;

/** 일반 회원가입의 결과이며, 가입 후 별도 로그인이 필요함을 안내합니다. */
public record SignupResponse(
        UUID userId,
        UserStatus status,
        boolean loginRequired
) {

    public static SignupResponse from(SignupResult result) {
        return new SignupResponse(result.userId(), result.status(), true);
    }
}

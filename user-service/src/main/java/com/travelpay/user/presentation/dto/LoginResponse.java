package com.travelpay.user.presentation.dto;

import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.application.dto.LoginResult;

/** 로그인 성공 응답이며 세션 ID·비밀번호·인증 토큰은 포함하지 않습니다. */
public record LoginResponse(UUID userId, AuthRole role) {

    public static LoginResponse from(LoginResult result) {
        return new LoginResponse(result.userId(), result.role());
    }
}

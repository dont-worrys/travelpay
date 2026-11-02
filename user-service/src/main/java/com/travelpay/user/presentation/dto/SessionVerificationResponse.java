package com.travelpay.user.presentation.dto;

import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;

/** 세션 확인 API 응답. role은 로그인 때 값이 아니라 이번 요청에서 DB로 확인한 현재 역할입니다. */
public record SessionVerificationResponse(UUID userId, AuthRole role) {

    public static SessionVerificationResponse from(AuthenticatedUser user) {
        return new SessionVerificationResponse(user.userId(), user.role());
    }
}

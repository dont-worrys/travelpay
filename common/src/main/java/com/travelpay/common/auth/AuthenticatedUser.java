package com.travelpay.common.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * 인증 검증을 마친 요청자의 회원 ID와 역할을 전달합니다.
 * 세션 검증 결과 또는 신뢰가 확인된 내부 요청을 바탕으로 생성해야 합니다.
 */
public record AuthenticatedUser(UUID userId, AuthRole role) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}

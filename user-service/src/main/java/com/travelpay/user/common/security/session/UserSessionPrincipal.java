package com.travelpay.user.common.security.session;

import java.io.Serializable;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;

/**
 * 정식 회원의 로그인 인증 정보이며, HTTP 세션에 저장할 최소 값입니다.
 * 역할은 로그인 시점의 값이므로, 현재 회원 상태와 권한은 DB에서 별도로 확인합니다.
 */
public record UserSessionPrincipal(UUID userId, AuthRole role, Instant authenticatedAt)
        implements Principal, Serializable {

    public UserSessionPrincipal {
        if (userId == null) {
            throw new IllegalArgumentException("로그인한 회원의 식별자가 필요합니다.");
        }
        if (role == null) {
            throw new IllegalArgumentException("로그인한 회원의 역할이 필요합니다.");
        }
        if (authenticatedAt == null) {
            throw new IllegalArgumentException("로그인 성공 시각이 필요합니다.");
        }
    }

    /** 로그인 시각부터 absoluteTimeout이 지나면 활동과 관계없이 만료입니다. 세션 ID를 바꿔도 이 시각은 그대로입니다. */
    public boolean isExpiredAt(Instant now, Duration absoluteTimeout) {
        return !now.isBefore(authenticatedAt.plus(absoluteTimeout));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}

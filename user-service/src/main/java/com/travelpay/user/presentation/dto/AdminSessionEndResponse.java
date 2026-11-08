package com.travelpay.user.presentation.dto;

import java.util.UUID;

/** 관리자 회원 세션 종료 응답입니다. 범위는 종료 시점까지 발급된 세션이며, 이후의 새 로그인은 막지 않습니다. */
public record AdminSessionEndResponse(UUID userId, String scope) {

    private static final String EXISTING_SESSIONS = "EXISTING_SESSIONS";

    public static AdminSessionEndResponse of(UUID userId) {
        return new AdminSessionEndResponse(userId, EXISTING_SESSIONS);
    }
}

package com.travelpay.user.application.dto;

import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.domain.model.entity.User;

/** 자격 증명과 현재 회원 상태 확인을 통과한 회원의 로그인 결과입니다. */
public record LoginResult(UUID userId, AuthRole role) {

    public static LoginResult from(User user) {
        return new LoginResult(user.getId(), user.getRole());
    }
}

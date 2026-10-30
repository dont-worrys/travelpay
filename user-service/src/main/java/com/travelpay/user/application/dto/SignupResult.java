package com.travelpay.user.application.dto;

import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;

/** 표현 계층에 전달할 회원가입 결과이며, 저장된 회원의 ID와 상태만 담습니다. */
public record SignupResult(
        UUID userId,
        UserStatus status
) {

    public static SignupResult from(User user) {
        return new SignupResult(user.getId(), user.getStatus());
    }
}

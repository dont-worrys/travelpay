package com.travelpay.user.presentation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.application.dto.MyInfoResult;
import com.travelpay.user.domain.model.vo.LoginMethod;
import com.travelpay.user.domain.model.vo.UserStatus;
import org.jspecify.annotations.Nullable;

/**
 * 마이페이지 본인 정보 응답입니다. 관리 목록·수취인 응답에는 이 DTO를 재사용하지 않습니다.
 * 개인정보를 담으므로 문자열 표현에는 값을 넣지 않습니다.
 */
public record MyInfoResponse(
        UUID userId,
        String realName,
        String phone,
        @Nullable String loginEmail,
        UserStatus status,
        AuthRole role,
        List<LoginMethod> loginMethods,
        Instant createdAt
) {

    public static MyInfoResponse from(MyInfoResult result) {
        return new MyInfoResponse(result.userId(), result.realName(), result.phone(), result.loginEmail(),
                result.status(), result.role(), result.loginMethods(), result.createdAt());
    }

    @Override
    public String toString() {
        return "MyInfoResponse[REDACTED]";
    }
}

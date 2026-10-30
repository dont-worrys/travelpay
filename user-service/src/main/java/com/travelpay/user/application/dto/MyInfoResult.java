package com.travelpay.user.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.LoginMethod;
import com.travelpay.user.domain.model.vo.UserStatus;
import org.jspecify.annotations.Nullable;

/**
 * 마이페이지에 보여 줄 본인 정보입니다. 자체 로그인 수단이 없으면 loginEmail은 null입니다.
 * 개인정보를 담으므로 문자열 표현에는 값을 넣지 않습니다.
 */
public record MyInfoResult(
        UUID userId,
        String realName,
        String phone,
        @Nullable String loginEmail,
        UserStatus status,
        AuthRole role,
        List<LoginMethod> loginMethods,
        Instant createdAt
) {

    public MyInfoResult {
        loginMethods = List.copyOf(loginMethods);
    }

    public static MyInfoResult from(User user, @Nullable UserCredentials credentials) {
        return new MyInfoResult(user.getId(), user.getRealName(), user.getPhone(),
                credentials == null ? null : credentials.getLoginEmail(), user.getStatus(), user.getRole(),
                credentials == null ? List.of() : List.of(LoginMethod.PASSWORD), user.getCreatedAt());
    }

    @Override
    public String toString() {
        return "MyInfoResult[REDACTED]";
    }
}

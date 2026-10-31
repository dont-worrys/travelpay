package com.travelpay.user.application.dto;

import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;

/**
 * 내부 이름 조회(수취인·충전 예금주)의 결과이며, 회원 ID와 가입 때 입력한 성명만 담습니다.
 * 개인정보를 담으므로 문자열 표현에는 값을 넣지 않습니다.
 */
public record UserRealNameResult(UUID userId, String realName) {

    public static UserRealNameResult from(User user) {
        return new UserRealNameResult(user.getId(), user.getRealName());
    }

    @Override
    public String toString() {
        return "UserRealNameResult[REDACTED]";
    }
}

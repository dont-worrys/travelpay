package com.travelpay.user.presentation.dto;

import java.util.UUID;

import com.travelpay.user.application.dto.UserRealNameResult;

/**
 * 내부 이름 조회 응답입니다. 가입 때 입력한 성명을 가리지 않고 담으며 이메일·휴대폰 번호는 넣지 않습니다.
 * 화면에 보여 줄 이름의 마스킹은 호출한 서비스의 공개 응답이 맡습니다.
 * 개인정보를 담으므로 문자열 표현에는 값을 넣지 않습니다.
 */
public record UserRealNameResponse(UUID userId, String realName) {

    public static UserRealNameResponse from(UserRealNameResult result) {
        return new UserRealNameResponse(result.userId(), result.realName());
    }

    @Override
    public String toString() {
        return "UserRealNameResponse[REDACTED]";
    }
}

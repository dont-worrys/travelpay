package com.travelpay.payment.transfer.client;

import java.util.UUID;

/**
 * user-service 수취인 확인 응답입니다. realName은 마스킹 전 가입 성명이므로 외부 응답에는 마스킹한 값만 내보냅니다.
 * 로그 등에 성명이 남지 않도록 문자열 표현에는 값을 넣지 않습니다.
 */
public record UserRecipientResponse(UUID userId, String realName) {

    @Override
    public String toString() {
        return "UserRecipientResponse[REDACTED]";
    }
}

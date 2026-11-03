package com.travelpay.payment.application.transfer.client;

import java.util.UUID;

/**
 * 송금 받을 수 있는(ACTIVE) 회원입니다. realName은 마스킹 전 가입 성명이므로 외부 응답에는 마스킹한 값만 내보냅니다.
 * 로그 등에 성명이 남지 않도록 문자열 표현에는 값을 넣지 않습니다.
 */
public record TransferRecipient(UUID userId, String realName) {

    @Override
    public String toString() {
        return "TransferRecipient[REDACTED]";
    }
}

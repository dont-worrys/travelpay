package com.travelpay.user.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 비밀번호 변경의 DB 작업·잠금 대기에 적용할 트랜잭션 제한 시간입니다. */
@ConfigurationProperties("travelpay.user.password-change")
public record PasswordChangeProperties(Duration transactionTimeout) {

    public PasswordChangeProperties {
        if (transactionTimeout == null || transactionTimeout.isNegative() || transactionTimeout.isZero()
                || transactionTimeout.getNano() != 0 || transactionTimeout.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "travelpay.user.password-change.transaction-timeout은 단위를 붙인 1초 이상의 정수 초여야 합니다(예: 5s).");
        }
    }
}

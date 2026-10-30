package com.travelpay.user.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 로그인 세션 정책 값입니다. 유휴 만료는 spring.session.timeout을 사용합니다. */
@ConfigurationProperties("travelpay.user.session")
public record UserSessionProperties(Duration absoluteTimeout) {

    public UserSessionProperties {
        if (absoluteTimeout == null || absoluteTimeout.isNegative() || absoluteTimeout.isZero()) {
            throw new IllegalArgumentException("travelpay.user.session.absolute-timeout은 0보다 커야 합니다.");
        }
    }
}

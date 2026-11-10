package com.travelpay.gateway.auth;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 세션 확인 API의 주소와 연결·읽기·전체 대기 상한입니다. 값이 없거나 0 이하면 Gateway가 시작하지 않습니다. */
@ConfigurationProperties("travelpay.gateway.session-verification")
public record SessionVerificationProperties(
        URI uri, Duration connectTimeout, Duration responseTimeout, Duration totalTimeout) {

    public SessionVerificationProperties {
        if (uri == null) {
            throw new IllegalArgumentException("travelpay.gateway.session-verification.uri가 필요합니다.");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(responseTimeout, "response-timeout");
        requirePositive(totalTimeout, "total-timeout");
    }

    private static void requirePositive(Duration timeout, String name) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "travelpay.gateway.session-verification." + name + "은 0보다 커야 합니다: " + timeout);
        }
    }
}

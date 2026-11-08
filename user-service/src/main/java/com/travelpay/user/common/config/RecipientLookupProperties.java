package com.travelpay.user.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 송금 수취인 실명 조회의 요청자별 허용 횟수와 집계 기간입니다. */
@ConfigurationProperties("travelpay.user.recipient-lookup")
public record RecipientLookupProperties(int limit, Duration period) {

    private static final Duration MINIMUM_PERIOD = Duration.ofSeconds(1);
    // Lua와 Sorted Set의 double이 정수 밀리초를 정확히 표현하는 범위입니다.
    private static final Duration MAXIMUM_PERIOD = Duration.ofMillis(1L << 53);

    public RecipientLookupProperties {
        if (limit < 1) {
            throw new IllegalArgumentException("travelpay.user.recipient-lookup.limit은 1 이상이어야 합니다.");
        }
        if (period == null || period.compareTo(MINIMUM_PERIOD) < 0
                || period.compareTo(MAXIMUM_PERIOD) > 0) {
            throw new IllegalArgumentException(
                    "travelpay.user.recipient-lookup.period는 1초 이상이며 Redis가 밀리초를 정확히 표현할 수 있는 범위여야 합니다(예: 60s).");
        }
    }
}

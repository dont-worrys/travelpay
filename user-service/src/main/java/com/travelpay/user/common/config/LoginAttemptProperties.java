package com.travelpay.user.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 로그인 실패 횟수 제한 정책 값입니다. 횟수는 window 동안 세고, 한도에 닿으면 block 동안 막습니다. */
@ConfigurationProperties("travelpay.user.login-attempt")
public record LoginAttemptProperties(int pairLimit, int accountLimit, Duration window, Duration block) {

    // 단위를 빠뜨린 값(10 → 10ms)은 횟수가 곧바로 만료되고, 1ms 미만 값은 Redis 만료 시간이 0이 되어
    // 제한이 조용히 꺼지므로 받지 않습니다.
    private static final Duration MINIMUM_DURATION = Duration.ofSeconds(1);

    public LoginAttemptProperties {
        if (pairLimit < 1 || accountLimit < 1) {
            throw new IllegalArgumentException("travelpay.user.login-attempt의 pair-limit과 account-limit은 1 이상이어야 합니다.");
        }
        if (!isLongEnough(window) || !isLongEnough(block)) {
            throw new IllegalArgumentException(
                    "travelpay.user.login-attempt의 window와 block은 단위를 붙여 1초 이상으로 설정해야 합니다(예: 10m).");
        }
    }

    private static boolean isLongEnough(Duration duration) {
        return duration != null && duration.compareTo(MINIMUM_DURATION) >= 0;
    }
}

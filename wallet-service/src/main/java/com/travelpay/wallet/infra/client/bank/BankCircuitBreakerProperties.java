package com.travelpay.wallet.infra.client.bank;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 은행 코드별 서킷 브레이커 설정 (travelpay.client.bank.circuit-breaker.*).
 * 값이 없거나 범위를 벗어나면 IllegalStateException으로 기동을 멈춘다.
 * 느린 호출 판정은 끄는 값이라 설정으로 두지 않는다 (BankCircuitBreakerConfig 참고)
 */
@ConfigurationProperties(prefix = BankCircuitBreakerProperties.PREFIX)
public record BankCircuitBreakerProperties(Integer slidingWindowSize,
                                           Integer minimumNumberOfCalls,
                                           Float failureRateThreshold,
                                           Duration waitDurationInOpenState,
                                           Integer permittedNumberOfCallsInHalfOpenState) {

    static final String PREFIX = "travelpay.client.bank.circuit-breaker";

    public BankCircuitBreakerProperties {
        requireAtLeastOne("sliding-window-size", slidingWindowSize);
        requireAtLeastOne("minimum-number-of-calls", minimumNumberOfCalls);
        requireAtLeastOne("permitted-number-of-calls-in-half-open-state", permittedNumberOfCallsInHalfOpenState);
        if (minimumNumberOfCalls > slidingWindowSize) {
            // COUNT_BASED에서 최소 건수가 창보다 크면 Resilience4j가 창 크기로 줄여 쓰므로 설정 의도와 달라진다
            throw new IllegalStateException(PREFIX + ".minimum-number-of-calls는 sliding-window-size 이하여야 합니다: "
                    + minimumNumberOfCalls + " > " + slidingWindowSize);
        }
        if (failureRateThreshold == null || !(failureRateThreshold > 0 && failureRateThreshold <= 100)) {
            throw new IllegalStateException(PREFIX + ".failure-rate-threshold는 0 초과 100 이하여야 합니다: "
                    + failureRateThreshold);
        }
        if (waitDurationInOpenState == null || waitDurationInOpenState.toMillis() < 1) {
            throw new IllegalStateException(PREFIX + ".wait-duration-in-open-state는 1ms 이상이어야 합니다: "
                    + waitDurationInOpenState);
        }
    }

    private static void requireAtLeastOne(String key, Integer value) {
        if (value == null || value < 1) {
            throw new IllegalStateException(PREFIX + "." + key + "는 1 이상이어야 합니다: " + value);
        }
    }
}

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

    /**
     * 창 크기(= 열릴 때까지의 결과 모름 연속 수)의 이론 상한. 이보다 크면 확실히 위험하다는 값이며, 이하라고 안전을 보장하지 않는다.
     * <ul>
     *     <li>max.poll.interval.ms 300초는 한 번 poll한 레코드를 모두 처리하는 시간의 제한이다
     *         (기본값, 2026-11-10 기동 로그 확인). 같은 로그에서 max.poll.records는 500</li>
     *     <li>연속 실패 수 × 6초(연결 1초 + 읽기 5초, 호출 한 번 6초 이하 전제)
     *         + 나머지 레코드 처리(500 × 12.6ms ≈ 6.3초) &lt; 300초 → 48 이하.
     *         12.6ms는 2026-11-10 Docker 측정 환경에서 동시성 1의 처리 한도 79.6건/초로 나눈 정상 충전 한 건의 처리 시간</li>
     *     <li>서킷이 열린 뒤 나머지 레코드는 보내지 않아 곧 끝나고, 반열림은 10초 뒤라 같은 poll 묶음 안에서 시험 호출이 더해지지 않는다</li>
     * </ul>
     * 48 근처는 여유가 약 6초뿐이고 한 건 처리 시간은 측정 환경 값이라 부하가 크면 여유가 줄어든다. 운영 값은 측정으로 정한다(지금 5).
     * 장애 은행 하나 기준이며, 읽기 제한·poll 간격·poll 레코드 수를 바꾸면 다시 계산한다
     */
    static final int MAX_SLIDING_WINDOW_SIZE = 48;

    public BankCircuitBreakerProperties {
        requireAtLeastOne("sliding-window-size", slidingWindowSize);
        if (slidingWindowSize > MAX_SLIDING_WINDOW_SIZE) {
            throw new IllegalStateException(PREFIX + ".sliding-window-size는 " + MAX_SLIDING_WINDOW_SIZE
                    + " 이하여야 합니다 (연속 실패 수 × 6초 + 나머지 poll 레코드 처리 약 6.3초가 "
                    + "max.poll.interval.ms 300초 안에 끝나야 컨슈머가 그룹에서 빠지지 않음): " + slidingWindowSize);
        }
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

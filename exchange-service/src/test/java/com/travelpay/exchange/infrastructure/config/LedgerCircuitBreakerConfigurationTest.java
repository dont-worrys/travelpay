package com.travelpay.exchange.infrastructure.config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 원장 서킷 설정이 기준값대로 만들어지고, 무엇을 나쁜 호출로 세는지 확인합니다(#282). */
class LedgerCircuitBreakerConfigurationTest {

    private static final LedgerCircuitBreakerProperties DEFAULTS = new LedgerCircuitBreakerProperties(
            Duration.ofSeconds(10), 20, 50, Duration.ofSeconds(2), 50, Duration.ofSeconds(10), 5);

    @Test
    void mapsPropertiesToCircuitBreakerConfig() {
        CircuitBreakerConfig config = LedgerCircuitBreakerConfiguration.config(DEFAULTS);

        assertEquals(SlidingWindowType.TIME_BASED, config.getSlidingWindowType());
        assertEquals(10, config.getSlidingWindowSize());
        assertEquals(20, config.getMinimumNumberOfCalls());
        assertEquals(50f, config.getFailureRateThreshold());
        assertEquals(Duration.ofSeconds(2), config.getSlowCallDurationThreshold());
        assertEquals(50f, config.getSlowCallRateThreshold());
        assertEquals(5, config.getPermittedNumberOfCallsInHalfOpenState());
    }

    /** 결과 모름(시간 초과·연결 실패·5xx)만 실패로 센다. 거절·요청 문제는 원장이 건강하다는 뜻이라 세지 않는다 */
    @Test
    void recordsOnlyUnknownResultsAsFailures() {
        var failure = LedgerCircuitBreakerConfiguration.config(DEFAULTS).getRecordResultPredicate();

        assertTrue(failure.test(new LedgerPostResult.Unknown("timeout")));
        assertFalse(failure.test(new LedgerPostResult.Posted(UUID.randomUUID())));
        assertFalse(failure.test(new LedgerPostResult.Rejected("LEDGER_REJECTED_INSUFFICIENT_BALANCE")));
        assertFalse(failure.test(new LedgerPostResult.Invalid(LedgerPostResult.InvalidKind.CONFLICT)));
    }

    @Test
    void opensWhenHalfOfCallsAreUnknown() {
        CircuitBreaker circuit = circuit();

        for (int i = 0; i < 20; i++) {
            record(circuit, Duration.ofMillis(10), i % 2 == 0 ? new LedgerPostResult.Unknown("timeout") : posted());
        }

        assertEquals(CircuitBreaker.State.OPEN, circuit.getState());
    }

    /** 개선 전 측정(원장 3초)처럼 모두 성공했어도 2초 넘게 걸리면 느린 호출로 세어 연다 */
    @Test
    void opensWhenCallsAreSlowEvenIfTheySucceed() {
        CircuitBreaker circuit = circuit();

        for (int i = 0; i < 20; i++) {
            record(circuit, Duration.ofSeconds(3), posted());
        }

        assertEquals(CircuitBreaker.State.OPEN, circuit.getState());
    }

    /** 잔액 부족 같은 거절이 많아도 원장은 제대로 답한 것이라 열지 않는다 */
    @Test
    void staysClosedWhenLedgerRejectsQuickly() {
        CircuitBreaker circuit = circuit();

        for (int i = 0; i < 40; i++) {
            record(circuit, Duration.ofMillis(10), new LedgerPostResult.Rejected("LEDGER_REJECTED_INSUFFICIENT_BALANCE"));
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuit.getState());
    }

    /** 최소 호출 수(20건)보다 적으면 모두 실패여도 열지 않는다 */
    @Test
    void staysClosedBelowMinimumCalls() {
        CircuitBreaker circuit = circuit();

        for (int i = 0; i < 19; i++) {
            record(circuit, Duration.ofMillis(10), new LedgerPostResult.Unknown("timeout"));
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuit.getState());
    }

    @Test
    void createsNamedBeanWithMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        CircuitBreaker circuit = new LedgerCircuitBreakerConfiguration().exchangeLedgerCircuitBreaker(DEFAULTS, meters);

        assertEquals("exchangeLedger", circuit.getName());
        assertNotNull(meters.find("resilience4j.circuitbreaker.state").tag("name", "exchangeLedger").gauge());
    }

    private static CircuitBreaker circuit() {
        return CircuitBreaker.of("test", LedgerCircuitBreakerConfiguration.config(DEFAULTS));
    }

    /** 원장 호출 한 건을 흉내 낸다: 허가를 받고 걸린 시간과 결과를 알린다 */
    private static void record(CircuitBreaker circuit, Duration took, LedgerPostResult result) {
        circuit.acquirePermission();
        circuit.onResult(took.toNanos(), TimeUnit.NANOSECONDS, result);
    }

    private static LedgerPostResult posted() {
        return new LedgerPostResult.Posted(UUID.randomUUID());
    }
}

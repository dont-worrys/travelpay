package com.travelpay.wallet.infra.client.bank;

import com.travelpay.wallet.application.charge.dto.BankResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 은행 출금·환불(BankClientAdapter)에 쓰는 은행 코드별 서킷 브레이커. 레지스트리 하나에서 은행 코드를 이름으로 서킷을 만든다.
 * 은행 하나가 응답 없이 붙잡을 때 그 은행 호출이 매번 타임아웃까지 기다리지 않도록, 결과 모름이 이어지면 보내지 않는다(NotSent).
 * 다른 은행의 호출은 막지 않는다. 거래 조회(BankTransactionQueryAdapter)는 서킷을 거치지 않는다.
 * <ul>
 *     <li>상태: resilience4j_circuitbreaker_state{name="은행 코드"}</li>
 *     <li>열린 횟수: bank_circuit_opened_total{bank="은행 코드"} (OPEN으로 바뀔 때 +1)</li>
 *     <li>상태가 바뀌면 WARN 로그</li>
 * </ul>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BankCircuitBreakerProperties.class)
public class BankCircuitBreakerConfig {

    static final String OPENED_METRIC = "bank.circuit.opened";

    /**
     * 느린 호출 판정을 끄는 기준 시간 (Resilience4j 2.3.0 소스 기준).
     * <ul>
     *     <li>slowCallRateThreshold는 0 초과 100 이하만 허용해 비율로는 끌 수 없다 (CircuitBreakerConfig.Builder)</li>
     *     <li>판정은 호출 시간(나노초) &gt; 기준 시간(나노초)이고 기준 시간은 하한(1ns)만 있다 (CircuitBreakerMetrics)</li>
     *     <li>long 나노초는 Long.MAX_VALUE를 넘을 수 없어 이 값이면 느린 호출이 생기지 않는다.
     *         더 긴 Duration은 서킷을 만들 때 toNanos()에서 ArithmeticException이 나므로 이 값이 상한이다</li>
     * </ul>
     * 끄는 이유: 읽기 제한(5초)은 읽기 한 번당 제한이라 호출 전체가 더 길 수 있고, 정책은 결과 모름 연속만으로 연다
     */
    static final Duration SLOW_CALL_DISABLED = Duration.ofNanos(Long.MAX_VALUE);

    @Bean
    public CircuitBreakerRegistry bankCircuitBreakerRegistry(BankCircuitBreakerProperties properties,
                                                             MeterRegistry meterRegistry) {
        return registry(config(properties), meterRegistry);
    }

    /** 레지스트리에 지표·상태 전환 로그를 붙인다. 서킷은 은행 코드로 처음 부를 때 만들어진다 */
    static CircuitBreakerRegistry registry(CircuitBreakerConfig config, MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        registry.getEventPublisher().onEntryAdded(event -> watch(event.getAddedEntry(), meterRegistry));
        return registry;
    }

    private static void watch(CircuitBreaker circuitBreaker, MeterRegistry meterRegistry) {
        String bankCode = circuitBreaker.getName();
        Counter opened = Counter.builder(OPENED_METRIC)
                .description("은행 서킷이 OPEN으로 바뀐 횟수")
                .tag("bank", bankCode)
                .register(meterRegistry);
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            log.warn("은행 서킷 상태 변경 bankCode={} transition={}", bankCode, event.getStateTransition());
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                opened.increment();
            }
        });
    }

    /**
     * 최근 호출(sliding-window-size건, 최소 minimum-number-of-calls건)에서 결과 모름 비율이 failure-rate-threshold 이상이면 연다.
     * 실패로 세는 것은 BankResult.Unknown뿐이고 업무 거절(Failed)·응답 이상(Invalid)·성공은 은행이 답한 것이라 성공으로 센다.
     * 열린 뒤 wait-duration-in-open-state가 지나면 자동으로 반열림하고, 시험 호출 결과로 닫거나 다시 연다
     */
    static CircuitBreakerConfig config(BankCircuitBreakerProperties properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .recordResult(result -> result instanceof BankResult.Unknown)
                .slowCallDurationThreshold(SLOW_CALL_DISABLED)
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(properties.permittedNumberOfCallsInHalfOpenState())
                .build();
    }
}

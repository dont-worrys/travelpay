package com.travelpay.payment.infra.client.ledger;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제·환불·정산의 원장 거래 등록(PaymentLedgerFeignClient)에 쓰는 서킷 브레이커입니다.
 * 원장이 느리거나 응답하지 않을 때 결제 요청이 원장 응답 시간 초과(6초)까지 스레드를 잡고 기다리지 않도록,
 * 장애가 이어지면 원장에 보내지 않고 바로 결과를 돌려줍니다(NOT_SENT → 결제는 PENDING, 복구 스케줄러가 다시 요청).
 * 상태는 Prometheus 지표 resilience4j_circuitbreaker_state{name="paymentLedger"}로 내보냅니다.
 */
@Slf4j
@Configuration
public class PaymentLedgerCircuitBreakerConfig {

    static final String NAME = "paymentLedger";

    @Bean
    public CircuitBreaker paymentLedgerCircuitBreaker(MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        CircuitBreaker circuitBreaker = registry.circuitBreaker(NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("원장 서킷 상태 변경 {}", event.getStateTransition()));
        return circuitBreaker;
    }

    /**
     * 최근 10초 동안 20건 이상 호출됐고, 그중 절반 이상이 실패(시간 초과·연결 실패·5xx)이거나 2초 이상 걸리면 엽니다.
     * 열린 뒤 10초 동안은 원장에 보내지 않고, 그 뒤 5건만 시험으로 보내 결과를 보고 닫거나 다시 엽니다.
     * 평소 원장 응답은 수~수십 ms라 2초 이상이면 이상 신호로 봅니다.
     */
    static CircuitBreakerConfig config() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(20)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
    }
}

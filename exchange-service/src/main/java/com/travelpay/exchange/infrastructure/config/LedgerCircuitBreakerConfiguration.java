package com.travelpay.exchange.infrastructure.config;

import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 원장 거래 등록(LedgerClient.post)에 쓰는 서킷 브레이커입니다(#282).
 *
 * 원장 거래 등록은 환전 신청(요청 스레드), 복구 스케줄러, 예약 체결(환율 수집 스레드) 세 곳이 모두 LedgerClientAdapter.post 한 곳을
 * 거치므로, 서킷 하나로 세 경로를 함께 막습니다. 원장이 느리거나 응답하지 않으면 각 경로가 원장 응답(최대 약 5초)을 기다리며
 * 스레드를 붙잡는데, 열린 동안은 원장에 보내지 않고 바로 결과 모름으로 처리해 주문을 PROCESSING으로 두고 다음 일로 넘어갑니다.
 * 결과는 원장이 회복된 뒤 복구 스케줄러가 확정합니다.
 *
 * 실패로 세는 것은 결과 모름(Unknown: 시간 초과·연결 실패·5xx)뿐입니다. 거절(Rejected, 잔액 부족 등)은 원장이 정상적으로 답한
 * 것이고, Invalid(400·409 등)는 원장 장애가 아니라 요청 문제라 세지 않습니다. 느린 호출은 결과와 상관없이 걸린 시간으로 셉니다.
 * 상태는 Prometheus 지표 resilience4j_circuitbreaker_state{name="exchangeLedger"}로 내보내고, 바뀔 때 WARN 로그를 남깁니다.
 * 관리자 처리의 원장 조회(findByOrderId)는 사람이 가끔 하는 일이고 실제 원장 상태를 보여야 해서 감싸지 않습니다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerCircuitBreakerProperties.class)
public class LedgerCircuitBreakerConfiguration {

    static final String NAME = "exchangeLedger";

    @Bean
    public CircuitBreaker exchangeLedgerCircuitBreaker(LedgerCircuitBreakerProperties properties, MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config(properties));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        CircuitBreaker circuitBreaker = registry.circuitBreaker(NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("원장 서킷 상태 변경 {}", event.getStateTransition()));
        return circuitBreaker;
    }

    static CircuitBreakerConfig config(LedgerCircuitBreakerProperties properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(Math.toIntExact(properties.slidingWindow().toSeconds()))
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRatePercent())
                .slowCallDurationThreshold(properties.slowCallDuration())
                .slowCallRateThreshold(properties.slowCallRatePercent())
                .waitDurationInOpenState(properties.waitInOpen())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                // 원장 클라이언트는 실패를 예외 대신 결과로 돌려주므로, 결과 모름만 실패로 센다
                .recordResult(result -> result instanceof LedgerPostResult.Unknown)
                .build();
    }
}

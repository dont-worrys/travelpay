package com.travelpay.exchange.infrastructure.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 원장 거래 등록 서킷 브레이커 설정(exchange.ledger.circuit-breaker)입니다(#282). 기본값은 결제 서비스의 원장 서킷과 같습니다.
 *
 * 최근 slidingWindow 동안 원장 호출이 minimumCalls건 이상이고, 그중 실패(시간 초과·연결 실패·5xx)가 failureRatePercent% 이상이거나
 * slowCallDuration 이상 걸린 호출이 slowCallRatePercent% 이상이면 엽니다. 열린 뒤 waitInOpen 동안은 원장에 보내지 않고,
 * 그 뒤 halfOpenCalls건만 시험으로 보내 결과를 보고 닫거나 다시 엽니다.
 * 원장 응답은 평소 수~수십 ms라 2초 이상이면 이상 신호로 봅니다. 개선 전 측정(2026-11-12)에서 원장이 3초면 실패 없이도
 * 예약 체결이 환율 수집을 120초 내내 멈춰, 실패만 세지 않고 느린 호출도 셉니다.
 *
 * @param slidingWindow       판단 창(최근 몇 초의 호출을 보나)
 * @param minimumCalls        판단에 필요한 최소 호출 수 (창 안에 이보다 적으면 열지 않음)
 * @param failureRatePercent  실패율 기준(%)
 * @param slowCallDuration    느린 호출 기준. 이보다 오래 걸린 호출은 성공이어도 느린 호출로 셈 (응답 대기 한도 5초와 별개)
 * @param slowCallRatePercent 느린 호출 비율 기준(%)
 * @param waitInOpen          열린 뒤 원장에 보내지 않는 시간
 * @param halfOpenCalls       반열림 상태에서 시험으로 보내는 호출 수
 */
@Validated
@ConfigurationProperties("exchange.ledger.circuit-breaker")
public record LedgerCircuitBreakerProperties(
        @NotNull @DefaultValue("10s") Duration slidingWindow,
        @Positive @DefaultValue("20") int minimumCalls,
        @Positive @Max(100) @DefaultValue("50") int failureRatePercent,
        @NotNull @DefaultValue("2s") Duration slowCallDuration,
        @Positive @Max(100) @DefaultValue("50") int slowCallRatePercent,
        @NotNull @DefaultValue("10s") Duration waitInOpen,
        @Positive @DefaultValue("5") int halfOpenCalls
) {
}

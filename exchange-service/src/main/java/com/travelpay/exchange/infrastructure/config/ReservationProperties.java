package com.travelpay.exchange.infrastructure.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 예약 환전 설정(exchange.reservation)입니다.
 *
 * @param maxPeriod         예약할 수 있는 최대 기간 (기본 30일)
 * @param maxWaitingPerUser 회원 한 명이 동시에 걸어 둘 수 있는 대기 예약 수 (기본 10)
 */
@Validated
@ConfigurationProperties("exchange.reservation")
public record ReservationProperties(
        @NotNull @DefaultValue("30d") Duration maxPeriod,
        @Positive @DefaultValue("10") int maxWaitingPerUser
) {
}

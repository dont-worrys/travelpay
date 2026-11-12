package com.travelpay.exchange.domain.reservation;

import java.time.Duration;
import java.util.Objects;

/**
 * 예약 환전 정책입니다. 값은 설정(exchange.reservation)에서 받습니다.
 *
 * @param maxPeriod          예약할 수 있는 최대 기간 (지금부터 만료 시각까지)
 * @param maxWaitingPerUser  회원 한 명이 동시에 걸어 둘 수 있는 대기 예약 수
 */
public record ReservationPolicy(Duration maxPeriod, int maxWaitingPerUser) {

    public ReservationPolicy {
        Objects.requireNonNull(maxPeriod, "maxPeriod");
        if (maxPeriod.isNegative() || maxPeriod.isZero()) {
            throw new IllegalArgumentException("maxPeriod must be positive");
        }
        if (maxWaitingPerUser < 1) {
            throw new IllegalArgumentException("maxWaitingPerUser must be at least 1");
        }
    }
}

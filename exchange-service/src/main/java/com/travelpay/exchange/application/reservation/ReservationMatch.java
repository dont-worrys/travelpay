package com.travelpay.exchange.application.reservation;

import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.rate.ExchangeRate;

/**
 * 새로 수집한 환율 하나와, 그 환율로 체결 조건에 닿은 대기 예약 ID들입니다.
 * 체결할 때 이 환율로 견적을 계산하고, 행을 잠근 뒤 조건을 한 번 더 확인합니다.
 */
public record ReservationMatch(ExchangeRate rate, List<UUID> reservationIds) {

    public ReservationMatch {
        reservationIds = List.copyOf(reservationIds);
    }
}

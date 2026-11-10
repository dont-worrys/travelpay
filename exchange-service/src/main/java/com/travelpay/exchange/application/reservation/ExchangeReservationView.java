package com.travelpay.exchange.application.reservation;

import java.util.Objects;

import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;

/**
 * 예약과, 체결됐다면 그 주문의 상태입니다. 체결 결과(완료·실패)는 주문 상태로 알 수 있습니다.
 *
 * @param orderStatus 체결 전이면 null
 */
public record ExchangeReservationView(ExchangeReservation reservation, ExchangeOrderStatus orderStatus) {

    public ExchangeReservationView {
        Objects.requireNonNull(reservation, "reservation");
    }
}

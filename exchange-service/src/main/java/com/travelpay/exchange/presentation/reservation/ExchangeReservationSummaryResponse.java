package com.travelpay.exchange.presentation.reservation;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.domain.reservation.ExchangeReservation;

/**
 * 예약 환전 목록의 한 줄입니다. 목록 화면에 필요한 값만 담고, 체결된 주문의 상태는 상세 조회에서 내려줍니다
 * (행마다 주문을 조회하지 않게).
 */
public record ExchangeReservationSummaryResponse(
        UUID reservationId,
        String type,
        String status,
        String fromCurrency,
        String fromAmount,
        String toCurrency,
        String targetRate,
        int rateUnit,
        String condition,
        Instant expiresAt,
        UUID orderId,
        Instant createdAt
) {

    public static ExchangeReservationSummaryResponse from(ExchangeReservation reservation) {
        return new ExchangeReservationSummaryResponse(
                reservation.getId(),
                ReservationResponses.type(reservation),
                reservation.getStatus().name(),
                reservation.getFromCurrency().name(),
                reservation.fromMoney().amount().toPlainString(),
                reservation.getToCurrency().name(),
                ReservationResponses.rate(reservation),
                reservation.getRateUnit(),
                ReservationResponses.condition(reservation),
                reservation.getExpiresAt(),
                reservation.getOrderId(),
                reservation.getCreatedAt()
        );
    }
}

package com.travelpay.exchange.presentation.reservation;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.application.reservation.ExchangeReservationView;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;

/**
 * 예약 환전 상세 응답입니다. 금액과 환율은 부동소수점 오차 없이 통화 단위 문자열로 내려줍니다.
 *
 * @param type        EXCHANGE(원화 → 외화) 또는 RE_EXCHANGE(외화 → 원화)
 * @param status      WAITING(대기), EXECUTED(체결: 주문 생성), CANCELED(취소), EXPIRED(만료)
 * @param targetRate  목표 매매기준율. rateUnit 단위 외화의 원화 가격
 * @param condition   체결 조건. AT_OR_BELOW(환율이 목표 이하일 때) 또는 AT_OR_ABOVE(목표 이상일 때)
 * @param orderId     체결로 만든 주문 ID. 체결 전이면 null
 * @param orderStatus 체결로 만든 주문의 상태(PROCESSING·COMPLETED·FAILED·MANUAL_REVIEW). 체결 전이면 null
 * @param executedAt  체결 시각. 체결 전이면 null
 */
public record ExchangeReservationResponse(
        UUID reservationId,
        String type,
        String status,
        UUID walletId,
        String fromCurrency,
        String fromAmount,
        String toCurrency,
        String targetRate,
        int rateUnit,
        String condition,
        Instant expiresAt,
        UUID orderId,
        String orderStatus,
        Instant executedAt,
        Instant createdAt
) {

    public static ExchangeReservationResponse from(ExchangeReservationView view) {
        ExchangeReservation reservation = view.reservation();
        return new ExchangeReservationResponse(
                reservation.getId(),
                ReservationResponses.type(reservation),
                reservation.getStatus().name(),
                reservation.getWalletId(),
                reservation.getFromCurrency().name(),
                reservation.fromMoney().amount().toPlainString(),
                reservation.getToCurrency().name(),
                ReservationResponses.rate(reservation),
                reservation.getRateUnit(),
                ReservationResponses.condition(reservation),
                reservation.getExpiresAt(),
                reservation.getOrderId(),
                view.orderStatus() != null ? view.orderStatus().name() : null,
                reservation.getExecutedAt(),
                reservation.getCreatedAt()
        );
    }
}

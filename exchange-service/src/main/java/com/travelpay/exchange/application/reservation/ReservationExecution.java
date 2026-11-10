package com.travelpay.exchange.application.reservation;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrder;

/**
 * 예약 하나를 체결하려 한 결과입니다.
 *
 * @param order 체결로 만든 주문(원장 처리 후 최신 상태). 체결하지 않았으면 empty
 */
public record ReservationExecution(UUID reservationId, Outcome outcome, Optional<ExchangeOrder> order) {

    public enum Outcome {
        /** 주문을 만들고 예약을 체결로 바꿈 */
        EXECUTED,
        /** 체결하지 않음: 이미 대기가 아님(취소·체결·만료), 다른 곳이 처리 중(잠김), 조건에서 벗어남, 만료 시각 지남 */
        SKIPPED
    }

    static ReservationExecution executed(UUID reservationId, ExchangeOrder order) {
        return new ReservationExecution(reservationId, Outcome.EXECUTED, Optional.of(order));
    }

    static ReservationExecution skipped(UUID reservationId) {
        return new ReservationExecution(reservationId, Outcome.SKIPPED, Optional.empty());
    }
}

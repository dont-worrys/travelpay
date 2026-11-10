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
        SKIPPED,
        /** 체결할 수 없어 시스템이 취소함: 지갑 없음·사용 불가, 체결 시점 규칙으로 주문 불가 */
        CANCELED,
        /** 지갑 서비스·원장 장애로 지금은 확인할 수 없음. 대기로 남기고 다음 수집 때 다시 시도 */
        RETRY_LATER
    }

    static ReservationExecution executed(UUID reservationId, ExchangeOrder order) {
        return new ReservationExecution(reservationId, Outcome.EXECUTED, Optional.of(order));
    }

    static ReservationExecution of(UUID reservationId, Outcome outcome) {
        if (outcome == Outcome.EXECUTED) {
            throw new IllegalArgumentException("체결 결과는 주문과 함께 만듭니다");
        }
        return new ReservationExecution(reservationId, outcome, Optional.empty());
    }
}

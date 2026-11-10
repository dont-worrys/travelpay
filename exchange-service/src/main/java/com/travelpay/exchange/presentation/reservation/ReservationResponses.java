package com.travelpay.exchange.presentation.reservation;

import com.travelpay.exchange.domain.reservation.ExchangeReservation;

/** 예약 상세·목록 응답이 같이 쓰는 표시 규칙입니다. */
final class ReservationResponses {

    private ReservationResponses() {
    }

    /** 환전·재환전 구분. 주문 응답의 type과 같은 값입니다. */
    static String type(ExchangeReservation reservation) {
        return reservation.buysForeign() ? "EXCHANGE" : "RE_EXCHANGE";
    }

    /** 외화를 사면 환율이 목표 이하일 때, 팔면 목표 이상일 때 체결합니다. */
    static String condition(ExchangeReservation reservation) {
        return reservation.buysForeign() ? "AT_OR_BELOW" : "AT_OR_ABOVE";
    }

    /** DB 소수 자릿수(8자리)의 뒤쪽 0을 지워 입력한 모양에 가깝게 보여 줍니다. 예: 830.00000000 → "830" */
    static String rate(ExchangeReservation reservation) {
        return reservation.getTargetRate().stripTrailingZeros().toPlainString();
    }
}

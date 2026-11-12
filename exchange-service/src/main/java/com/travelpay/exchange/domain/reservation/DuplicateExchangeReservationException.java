package com.travelpay.exchange.domain.reservation;

/** 같은 회원·같은 멱등 키의 예약이 이미 있어 저장하지 못했습니다. 예약 쪽은 기존 예약을 다시 조회해 응답합니다. */
public class DuplicateExchangeReservationException extends RuntimeException {

    public DuplicateExchangeReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}

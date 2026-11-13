package com.travelpay.exchange.application.reservation;

import java.util.List;

/**
 * 조건에 닿은 예약을 체결로 넘깁니다. 어떻게 넘길지(바로 실행, 메시지 큐)를 구현이 정합니다.
 * 지금은 체결 전용 스레드 하나에 넘기는 구현(AsyncReservationDispatcher)을 씁니다. 수집 스레드는 원장을 기다리지 않습니다(#282).
 * 서버 여러 대가 나눠 체결해야 할 만큼 예약이 많아지면 Kafka로 넘기고 소비자가 체결하는 구현으로 바꿀 수 있게 나눠 둡니다.
 */
public interface ReservationDispatcher {

    void dispatch(List<ReservationMatch> matches);
}

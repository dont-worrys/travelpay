package com.travelpay.exchange.application.reservation;

import java.util.List;

/**
 * 조건에 닿은 예약을 체결로 넘깁니다. 어떻게 넘길지(바로 실행, 메시지 큐)를 구현이 정합니다.
 * 지금은 수집 스레드에서 바로 체결하는 구현(SynchronousReservationDispatcher)을 씁니다.
 * 예약이 많아 수집이 늦어지면 Kafka로 넘기고 소비자가 체결하는 구현으로 바꿀 수 있게 나눠 둡니다.
 */
public interface ReservationDispatcher {

    void dispatch(List<ReservationMatch> matches);
}

package com.travelpay.exchange.application.reservation;

import java.util.List;

import com.travelpay.exchange.application.rate.NewRatesListener;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 새 환율이 저장되면 조건에 닿은 대기 예약을 찾아 체결로 넘깁니다.
 * 새로 저장한 인스턴스만 알림을 받으므로(같은 환율은 한 번만 저장됨), 서버가 여러 대여도 매칭은 한 곳에서 합니다.
 */
@Component
@RequiredArgsConstructor
public class ReservationRateListener implements NewRatesListener {

    private final ExchangeReservationMatcher matcher;
    private final ReservationDispatcher dispatcher;

    @Override
    public void onNewRates(List<ExchangeRate> saved) {
        List<ReservationMatch> matches = matcher.match(saved);
        if (!matches.isEmpty()) {
            dispatcher.dispatch(matches);
        }
    }
}

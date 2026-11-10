package com.travelpay.exchange.application.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 새로 수집한 환율로 체결 조건에 닿은 대기 예약을 찾습니다. 찾기만 하고 상태는 바꾸지 않습니다.
 * 환율은 수집할 때만 바뀌므로, 매 분 전체 예약을 훑지 않고 수집 직후 새 환율로 한 번만 찾습니다.
 * 통화마다 조회 1번이고, 대기 예약 중 조건에 닿은 것만 부분 인덱스로 읽습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeReservationMatcher {

    private final ExchangeReservationRepository reservationRepository;
    private final Clock clock;

    /** 환율마다 조건에 닿은 예약을 찾습니다. 닿은 예약이 없는 환율은 결과에서 뺍니다. */
    public List<ReservationMatch> match(List<ExchangeRate> rates) {
        Instant now = clock.instant();
        List<ReservationMatch> matches = new ArrayList<>();
        for (ExchangeRate rate : rates) {
            List<UUID> ids = reservationRepository.findTriggeredWaitingIds(
                    rate.getCurrency().currency(), rate.getMidRate(), now);
            if (!ids.isEmpty()) {
                log.info("예약 조건 충족: currency={}, midRate={}, count={}", rate.getCurrency(), rate.getMidRate(), ids.size());
                matches.add(new ReservationMatch(rate, ids));
            }
        }
        return matches;
    }
}

package com.travelpay.exchange.application.reservation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.exchange.application.reservation.ReservationExecution.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 조건에 닿은 예약을 부른 스레드(수집 스레드)에서 하나씩 바로 체결합니다.
 * 예약 하나가 예상 밖 오류로 실패해도 나머지는 계속 체결합니다. 실패한 예약은 대기로 남아 다음 새 환율 때 다시 매칭됩니다.
 * 예약마다 지갑 확인·원장 호출이 있어, 한 번에 체결할 예약이 많으면 그만큼 다음 수집이 늦어집니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SynchronousReservationDispatcher implements ReservationDispatcher {

    private final ExchangeReservationExecutor executor;

    @Override
    public void dispatch(List<ReservationMatch> matches) {
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int failed = 0;
        for (ReservationMatch match : matches) {
            for (UUID reservationId : match.reservationIds()) {
                try {
                    outcomes.merge(executor.execute(reservationId, match.rate()).outcome(), 1, Integer::sum);
                } catch (RuntimeException e) {
                    failed++;
                    log.error("예약 체결 중 오류로 대기로 남깁니다: reservationId={}", reservationId, e);
                }
            }
        }
        log.info("예약 체결 결과: outcomes={}, failed={}", outcomes, failed);
    }
}

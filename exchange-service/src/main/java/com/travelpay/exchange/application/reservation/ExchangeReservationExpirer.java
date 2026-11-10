package com.travelpay.exchange.application.reservation;

import java.time.Clock;
import java.time.Instant;

import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 만료 시각이 지난 대기 예약을 만료(EXPIRED)로 바꿉니다.
 * 만료된 예약은 이미 매칭·체결에서 빠지므로(만료 시각으로 거름), 이 작업은 사용자에게 보이는 상태와
 * 회원당 대기 예약 수(만료된 예약이 한도를 차지하지 않게)를 맞추는 일입니다. 몇 분 늦어도 체결은 잘못되지 않습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeReservationExpirer {

    /** 한 번 실행에서 도는 배치 수 상한. 만료가 몰려도 한 주기가 끝없이 길어지지 않게 하고, 남은 것은 다음 주기에 처리 */
    static final int MAX_BATCHES = 20;

    private final ExchangeReservationRepository reservationRepository;
    private final Clock clock;

    /**
     * 배치마다 짧은 트랜잭션으로 batchSize건씩 바꿉니다. 한 배치가 batchSize보다 적으면 남은 것이 없으므로 멈춥니다.
     *
     * @return 이번에 만료로 바꾼 수
     */
    public int expire(int batchSize) {
        Instant now = clock.instant();
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int expired = reservationRepository.expireWaiting(now, batchSize);
            total += expired;
            if (expired < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("예약 만료: count={}", total);
        }
        return total;
    }
}

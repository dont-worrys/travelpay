package com.travelpay.exchange.application.reservation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeReservationExpirerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private final ExchangeReservationRepository repository = mock(ExchangeReservationRepository.class);
    private final ExchangeReservationExpirer expirer = new ExchangeReservationExpirer(repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** 배치가 가득 차면 남은 것이 있을 수 있어 다음 배치를 돌고, 덜 차면 멈춘다 */
    @Test
    void repeatsBatchesUntilOneIsNotFull() {
        when(repository.expireWaiting(NOW, 100)).thenReturn(100, 100, 30);

        assertEquals(230, expirer.expire(100));
        verify(repository, times(3)).expireWaiting(NOW, 100);
    }

    /** 만료가 계속 몰려도 한 번 실행은 정해진 배치 수에서 끝내고 나머지는 다음 주기에 넘긴다 */
    @Test
    void stopsAtMaxBatchesPerRun() {
        when(repository.expireWaiting(NOW, 10)).thenReturn(10);

        assertEquals(10 * ExchangeReservationExpirer.MAX_BATCHES, expirer.expire(10));
        verify(repository, times(ExchangeReservationExpirer.MAX_BATCHES)).expireWaiting(NOW, 10);
    }
}

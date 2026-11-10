package com.travelpay.exchange.application.reservation;

import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SynchronousReservationDispatcherTest {

    private final ExchangeReservationExecutor executor = mock(ExchangeReservationExecutor.class);
    private final SynchronousReservationDispatcher dispatcher = new SynchronousReservationDispatcher(executor);

    /** 예약마다 그 예약을 찾은 환율로 체결하고, 하나가 예상 밖 오류로 실패해도 나머지는 계속 체결한다 */
    @Test
    void executesEachReservationWithItsRateAndContinuesAfterFailure() {
        ExchangeRate yen = QuoteFixtures.savedRate(ForeignCurrency.JPY, "820.00");
        ExchangeRate yuan = QuoteFixtures.savedRate(ForeignCurrency.CNY, "190.00");
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(executor.execute(failing, yen)).thenThrow(new IllegalStateException("db down"));
        when(executor.execute(next, yen)).thenReturn(ReservationExecution.of(next, ReservationExecution.Outcome.SKIPPED));
        when(executor.execute(other, yuan)).thenReturn(ReservationExecution.of(other, ReservationExecution.Outcome.SKIPPED));

        dispatcher.dispatch(List.of(new ReservationMatch(yen, List.of(failing, next)),
                new ReservationMatch(yuan, List.of(other))));

        verify(executor).execute(next, yen);
        verify(executor).execute(other, yuan);
    }
}

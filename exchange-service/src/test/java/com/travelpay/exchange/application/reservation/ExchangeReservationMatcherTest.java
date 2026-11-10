package com.travelpay.exchange.application.reservation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeReservationMatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private final ExchangeReservationRepository repository = mock(ExchangeReservationRepository.class);
    private final ExchangeReservationMatcher matcher = new ExchangeReservationMatcher(repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** 환율마다 그 외화·매매기준율·현재 시각으로 찾고, 닿은 예약이 없는 환율은 결과에서 뺀다 */
    @Test
    void matchesEachRateAndSkipsRatesWithoutTriggeredReservations() {
        ExchangeRate yen = QuoteFixtures.savedRate(ForeignCurrency.JPY, "830.00");
        ExchangeRate yuan = QuoteFixtures.savedRate(ForeignCurrency.CNY, "190.00");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findTriggeredWaitingIds(Currency.JPY, new BigDecimal("830.00"), NOW)).thenReturn(List.of(first, second));
        when(repository.findTriggeredWaitingIds(Currency.CNY, new BigDecimal("190.00"), NOW)).thenReturn(List.of());

        List<ReservationMatch> matches = matcher.match(List.of(yen, yuan));

        assertEquals(1, matches.size());
        assertSame(yen, matches.get(0).rate());
        assertEquals(List.of(first, second), matches.get(0).reservationIds());
    }

    @Test
    void noRatesMeansNoQuery() {
        assertTrue(matcher.match(List.of()).isEmpty());
        verifyNoInteractions(repository);
    }
}

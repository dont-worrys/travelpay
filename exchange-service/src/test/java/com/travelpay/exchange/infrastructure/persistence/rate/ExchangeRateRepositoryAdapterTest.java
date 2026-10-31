package com.travelpay.exchange.infrastructure.persistence.rate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.DuplicateExchangeRateException;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeRateRepositoryAdapterTest {

    private final ExchangeRateJpaRepository jpaRepository = mock(ExchangeRateJpaRepository.class);
    private final ExchangeRateRepositoryAdapter adapter = new ExchangeRateRepositoryAdapter(jpaRepository);
    private final ExchangeRate rate = ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100,
            new BigDecimal("838.3"), new BigDecimal("846.68"), new BigDecimal("829.91"),
            LocalDate.of(2026, 10, 28), RateSource.KOREAEXIM), Instant.parse("2026-10-28T02:10:00Z"));

    @Test
    void translatesDailyUniqueViolationToDuplicateException() {
        when(jpaRepository.saveAndFlush(rate))
                .thenThrow(violationOf(ExchangeRateRepositoryAdapter.DAILY_UNIQUE_INDEX));

        assertThrows(DuplicateExchangeRateException.class, () -> adapter.save(rate));
    }

    @Test
    void rethrowsViolationOfOtherConstraint() {
        DataIntegrityViolationException violation = violationOf("ck_exchange_rates_target_currency");
        when(jpaRepository.saveAndFlush(rate)).thenThrow(violation);

        assertSame(violation, assertThrows(DataIntegrityViolationException.class, () -> adapter.save(rate)));
    }

    @Test
    void rethrowsIntegrityFailureWithoutConstraintName() {
        // 숫자 자릿수 초과처럼 제약 이름이 없는 실패
        DataIntegrityViolationException overflow = new DataIntegrityViolationException("numeric field overflow");
        when(jpaRepository.saveAndFlush(rate)).thenThrow(overflow);

        assertSame(overflow, assertThrows(DataIntegrityViolationException.class, () -> adapter.save(rate)));
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("violation", new SQLException("violation", "23505"), constraintName));
    }
}

package com.travelpay.exchange.rate.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    Optional<ExchangeRate> findFirstBySourceAndCurrencyOrderByRateDateDescCollectedAtDesc(
            RateSource source, ForeignCurrency currency);

    boolean existsBySourceAndCurrencyAndRateDate(RateSource source, ForeignCurrency currency, LocalDate rateDate);
}

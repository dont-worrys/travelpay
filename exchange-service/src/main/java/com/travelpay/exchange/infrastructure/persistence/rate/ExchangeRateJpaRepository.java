package com.travelpay.exchange.infrastructure.persistence.rate;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExchangeRateJpaRepository extends JpaRepository<ExchangeRate, UUID> {

    Optional<ExchangeRate> findFirstBySourceAndCurrencyOrderByRateDateDescCollectedAtDesc(
            RateSource source, ForeignCurrency currency);

    boolean existsBySourceAndCurrencyAndRateDate(RateSource source, ForeignCurrency currency, LocalDate rateDate);
}

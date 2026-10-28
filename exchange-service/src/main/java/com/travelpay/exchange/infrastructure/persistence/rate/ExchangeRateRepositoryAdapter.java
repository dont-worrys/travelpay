package com.travelpay.exchange.infrastructure.persistence.rate;

import java.time.LocalDate;
import java.util.Optional;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 환율 저장소를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeRateRepositoryAdapter implements ExchangeRateRepository {

    private final ExchangeRateJpaRepository jpaRepository;

    @Override
    public ExchangeRate save(ExchangeRate exchangeRate) {
        return jpaRepository.save(exchangeRate);
    }

    @Override
    public Optional<ExchangeRate> findLatest(RateSource source, ForeignCurrency currency) {
        return jpaRepository.findFirstBySourceAndCurrencyOrderByRateDateDescCollectedAtDesc(source, currency);
    }

    @Override
    public boolean existsByRateDate(RateSource source, ForeignCurrency currency, LocalDate rateDate) {
        return jpaRepository.existsBySourceAndCurrencyAndRateDate(source, currency, rateDate);
    }
}

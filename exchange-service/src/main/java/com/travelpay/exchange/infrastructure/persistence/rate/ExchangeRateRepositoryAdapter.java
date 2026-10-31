package com.travelpay.exchange.infrastructure.persistence.rate;

import java.time.LocalDate;
import java.util.Optional;

import com.travelpay.exchange.domain.rate.DuplicateExchangeRateException;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.infrastructure.persistence.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** 도메인 환율 저장소를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeRateRepositoryAdapter implements ExchangeRateRepository {

    /** V1__create_exchange_rates.sql의 하루 1건 유니크 인덱스입니다. */
    static final String DAILY_UNIQUE_INDEX = "uk_exchange_rates_koreaexim_daily";

    private final ExchangeRateJpaRepository jpaRepository;

    /**
     * 유니크 위반을 이 자리에서 구분하도록 커밋까지 미루지 않고 바로 INSERT합니다.
     * 하루 1건 유니크 인덱스 위반만 도메인 예외로 바꾸고, CHECK·자릿수 초과 같은 다른 위반은 그대로 던집니다.
     */
    @Override
    public ExchangeRate save(ExchangeRate exchangeRate) {
        try {
            return jpaRepository.saveAndFlush(exchangeRate);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, DAILY_UNIQUE_INDEX)) {
                throw new DuplicateExchangeRateException("이미 저장된 고시분입니다: source=" + exchangeRate.getSource()
                        + ", currency=" + exchangeRate.getCurrency() + ", rateDate=" + exchangeRate.getRateDate());
            }
            throw e;
        }
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

package com.travelpay.exchange.domain.rate;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 환율 저장소입니다. 도메인은 저장 기술을 알지 못하며, 구현은 infrastructure 계층이 담당합니다.
 */
public interface ExchangeRateRepository {

    ExchangeRate save(ExchangeRate exchangeRate);

    /** 출처·통화별 가장 최근 고시일의 가장 최근 수집분입니다. */
    Optional<ExchangeRate> findLatest(RateSource source, ForeignCurrency currency);

    boolean existsByRateDate(RateSource source, ForeignCurrency currency, LocalDate rateDate);
}

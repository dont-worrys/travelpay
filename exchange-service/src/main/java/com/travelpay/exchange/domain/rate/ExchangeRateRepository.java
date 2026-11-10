package com.travelpay.exchange.domain.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 환율 저장소입니다. 도메인은 저장 기술을 알지 못하며, 구현은 infrastructure 계층이 담당합니다.
 */
public interface ExchangeRateRepository {

    /**
     * 환율을 바로 저장합니다.
     *
     * @throws DuplicateExchangeRateException 하루 한 번 고시하는 출처의 같은 통화·고시일 환율이 이미 있는 경우.
     *                                        그 밖의 저장 실패는 그대로 전달합니다.
     */
    ExchangeRate save(ExchangeRate exchangeRate);

    /** 출처·통화별 가장 최근 고시일의 가장 최근 수집분입니다. */
    Optional<ExchangeRate> findLatest(RateSource source, ForeignCurrency currency);

    /**
     * 여러 통화의 최신 환율을 쿼리 하나로 함께 읽습니다. 통화마다 findLatest와 같은 규칙으로 고릅니다.
     * 한 번에 읽으므로 모든 통화가 같은 시점(같은 수집 회차)의 데이터입니다.
     *
     * @return 요청한 통화 순서대로의 최신 환율. 아직 수집된 환율이 없는 통화는 빠집니다.
     */
    List<ExchangeRate> findLatestOfEach(RateSource source, List<ForeignCurrency> currencies);

    boolean existsByRateDate(RateSource source, ForeignCurrency currency, LocalDate rateDate);
}

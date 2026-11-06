package com.travelpay.exchange.application.rate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;

/**
 * 출처·통화별 최신 환율의 캐시입니다. 원본은 DB이고, 캐시는 수집기가 DB에 커밋한 최신 환율의 복사본입니다.
 * 통화마다 따로 다룹니다. 환전은 원화와 외화 하나 사이에서만 일어나 여러 통화를 한 계산에 함께 쓰지 않으므로,
 * 통화끼리 같은 수집 회차일 필요가 없고 한 통화가 없어도 다른 통화는 캐시에서 읽습니다.
 * 저장 기술(Redis)은 infrastructure가 구현합니다.
 * 캐시 저장소에 연결하지 못하는 등의 실패는 예외로 그대로 전달합니다. 실패 시 DB로 조회하는 판단은 호출하는 쪽이 합니다.
 */
public interface LatestRateCache {

    /**
     * 여러 통화의 최신 환율을 한 번에 읽습니다.
     *
     * @return 캐시에 있는 통화만 담은 통화별 최신 환율. 없는 통화는 빠지며, 그 통화만 DB에서 읽으면 됩니다.
     */
    Map<ForeignCurrency, ExchangeRate> findAll(RateSource source, List<ForeignCurrency> currencies);

    /** 한 통화의 최신 환율입니다. 캐시에 없으면 empty입니다. */
    Optional<ExchangeRate> find(RateSource source, ForeignCurrency currency);

    /**
     * 환율들을 한 번에 넣습니다. 통화마다 기존 값을 덮어씁니다.
     * 넣은 값은 정해진 시간(TTL)이 지나면 사라져, 갱신이 멈추면 DB 조회로 돌아갑니다.
     */
    void putAll(List<ExchangeRate> rates);
}

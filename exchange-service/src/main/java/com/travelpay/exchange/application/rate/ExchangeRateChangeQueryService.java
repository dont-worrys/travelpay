package com.travelpay.exchange.application.rate;

import java.util.List;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 최신 환율에 전일 대비 변동을 붙여 조회합니다(#272). 환율 조회 API가 씁니다.
 * 최신 환율은 ExchangeRateQueryService(캐시 → DB)로 읽고, 전일 환율은 PreviousRateCache(서버 메모리 → DB)로 읽습니다.
 * 견적 발급처럼 변동이 필요 없는 곳은 ExchangeRateQueryService를 그대로 씁니다.
 */
@Service
@RequiredArgsConstructor
public class ExchangeRateChangeQueryService {

    private final ExchangeRateQueryService queryService;
    private final PreviousRateCache previousRates;

    /** 지정한 통화별 최신 환율과 전일 대비입니다(요청한 통화 순서). 수집된 환율이 없는 통화는 빠집니다. */
    public List<LatestRateWithChange> findLatestWithChange(List<ForeignCurrency> currencies) {
        return queryService.findLatest(currencies).stream()
                .map(latest -> new LatestRateWithChange(latest, changeOf(latest)))
                .toList();
    }

    private RateChange changeOf(ExchangeRate latest) {
        return previousRates.find(latest)
                .map(previous -> RateChange.between(latest, previous))
                .orElse(null);
    }
}

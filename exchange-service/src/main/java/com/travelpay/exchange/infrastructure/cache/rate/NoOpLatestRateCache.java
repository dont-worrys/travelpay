package com.travelpay.exchange.infrastructure.cache.rate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.travelpay.exchange.application.rate.LatestRateCache;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 캐시를 끈 경우(exchange.rate.cache.enabled=false)의 구현입니다. 항상 비어 있어 모든 조회가 DB로 갑니다.
 * Redis를 쓰지 않는 테스트가 개발 PC의 Redis에 연결하지 않게 하고, 운영 중 캐시를 꺼야 할 때도 씁니다.
 */
@Component
@ConditionalOnProperty(name = "exchange.rate.cache.enabled", havingValue = "false")
public class NoOpLatestRateCache implements LatestRateCache {

    @Override
    public Map<ForeignCurrency, ExchangeRate> findAll(RateSource source, List<ForeignCurrency> currencies) {
        return Map.of();
    }

    @Override
    public Optional<ExchangeRate> find(RateSource source, ForeignCurrency currency) {
        return Optional.empty();
    }

    @Override
    public void putAll(List<ExchangeRate> rates) {
        // 캐시를 끈 상태라 넣지 않습니다.
    }
}

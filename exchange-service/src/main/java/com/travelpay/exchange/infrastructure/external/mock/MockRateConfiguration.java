package com.travelpay.exchange.infrastructure.external.mock;

import java.util.random.RandomGenerator;

import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * exchange.rate.mode=mock이면 가짜 환율 공급자와 그 모드의 정책을 함께 등록합니다.
 * koreaexim 모드와 같은 세 가지(공급자, 수집 정책, 신선도 정책)를 등록하므로, 수집·조회·견적 코드는 모드를 알지 못합니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "exchange.rate", name = "mode", havingValue = "mock")
@EnableConfigurationProperties(MockRateProperties.class)
public class MockRateConfiguration {

    @Bean
    public ExchangeRateProvider mockRateProvider(ExchangeRateRepository repository, MockRateProperties properties) {
        MockRateFluctuation fluctuation = new MockRateFluctuation(
                properties.maxStepRate(), properties.maxDeviationRate(), RandomGenerator.getDefault());
        return new MockRateProvider(repository, fluctuation, properties.defaultMidRates());
    }

    @Bean
    public RateCollectionPolicy mockCollectionPolicy(MockRateProperties properties) {
        return new MockCollectionPolicy(properties.interval());
    }

    @Bean
    public RateFreshnessPolicy mockFreshnessPolicy(MockRateProperties properties) {
        return new MockFreshnessPolicy(properties.staleAfter());
    }
}

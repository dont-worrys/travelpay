package com.travelpay.exchange.infrastructure.external.mock;

import java.util.SplittableRandom;
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
                properties.maxStepRate(), properties.maxDeviationRate(), newRandom());
        return new MockRateProvider(repository, fluctuation, properties.defaultMidRates());
    }

    /**
     * 가짜 환율에 쓸 난수 생성기입니다. java.base 모듈에 있는 SplittableRandom을 씁니다.
     * RandomGenerator.getDefault()는 기본 알고리즘(L32X64MixRandom)을 jdk.random 모듈에서 찾는데,
     * Temurin JRE 이미지에는 그 모듈이 없어 빈을 만들지 못하고 앱이 시작하지 않습니다.
     * 가짜 환율은 보안용 난수가 필요 없고, 한 스케줄러 스레드에서만 쓰므로 SplittableRandom으로 충분합니다.
     */
    static RandomGenerator newRandom() {
        return new SplittableRandom();
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

package com.travelpay.exchange.infrastructure.external.mock;

import java.math.BigDecimal;
import java.time.Duration;

import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** exchange.rate.mode=mock일 때만 가짜 공급자와 정책이 등록되고, 설정이 잘못되면 서버 시작 단계에서 실패하는지 확인합니다. */
class MockRateConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withConfiguration(UserConfigurations.of(MockRateConfiguration.class))
            .withBean(ExchangeRateRepository.class, () -> mock(ExchangeRateRepository.class))
            .withPropertyValues(
                    "exchange.rate.mock.default-mid-rates.JPY=900.00",
                    "exchange.rate.mock.default-mid-rates.CNY=190.00");

    @Test
    void registersMockProviderAndPoliciesInMockMode() {
        contextRunner.withPropertyValues("exchange.rate.mode=mock").run(context -> {
            assertEquals(RateSource.MOCK, context.getBean(ExchangeRateProvider.class).source());
            assertInstanceOf(MockCollectionPolicy.class, context.getBean(RateCollectionPolicy.class));
            assertInstanceOf(MockFreshnessPolicy.class, context.getBean(RateFreshnessPolicy.class));

            MockRateProperties properties = context.getBean(MockRateProperties.class);
            assertEquals(Duration.ofSeconds(5), properties.interval());
            assertEquals(new BigDecimal("0.0005"), properties.maxStepRate());
            assertEquals(new BigDecimal("0.02"), properties.maxDeviationRate());
            assertEquals(Duration.ofSeconds(30), properties.staleAfter());
            assertEquals(new BigDecimal("900.00"), properties.defaultMidRates().get(ForeignCurrency.JPY));
        });
    }

    @Test
    void registersNothingInOtherModes() {
        contextRunner.withPropertyValues("exchange.rate.mode=koreaexim").run(context -> {
            assertFalse(context.containsBean("mockRateProvider"));
            assertFalse(context.containsBean("mockCollectionPolicy"));
        });
    }

    @Test
    void failsToStartWhenDefaultRateIsMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withConfiguration(UserConfigurations.of(MockRateConfiguration.class))
                .withBean(ExchangeRateRepository.class, () -> mock(ExchangeRateRepository.class))
                .withPropertyValues("exchange.rate.mode=mock", "exchange.rate.mock.default-mid-rates.JPY=900.00")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWhenStepIsLargerThanRange() {
        contextRunner.withPropertyValues("exchange.rate.mode=mock", "exchange.rate.mock.max-step-rate=0.05")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWhenIntervalIsZero() {
        contextRunner.withPropertyValues("exchange.rate.mode=mock", "exchange.rate.mock.interval=0s")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}

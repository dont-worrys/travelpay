package com.travelpay.exchange.infrastructure.config;

import java.math.BigDecimal;
import java.time.Duration;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 견적 설정이 도메인 규칙으로 바뀌고, 규칙에 맞지 않으면 서버 시작 단계에서 실패하는지 확인합니다. */
class QuoteConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(QuoteConfiguration.class))
            .withPropertyValues(
                    "exchange.quote.limits.KRW.min=1000", "exchange.quote.limits.KRW.max=2000000",
                    "exchange.quote.limits.JPY.min=100", "exchange.quote.limits.JPY.max=200000",
                    "exchange.quote.limits.CNY.min=10.00", "exchange.quote.limits.CNY.max=10000.00");

    @Test
    void bindsDefaultsAndLimitsInCurrencyUnits() {
        contextRunner.run(context -> {
            QuotePolicy policy = context.getBean(QuotePolicy.class);
            assertEquals(Duration.ofMinutes(5), policy.ttl());
            assertEquals(new BigDecimal("0.01"), policy.feeRate().value());
            assertEquals(new Money(Currency.CNY, 1000), policy.limits().get(Currency.CNY).min());
            assertEquals(new Money(Currency.KRW, 2_000_000), policy.limits().get(Currency.KRW).max());
        });
    }

    @Test
    void failsToStartWhenCurrencyLimitIsMissing() {
        new ApplicationContextRunner()
                .withConfiguration(UserConfigurations.of(QuoteConfiguration.class))
                .withPropertyValues("exchange.quote.limits.KRW.min=1000", "exchange.quote.limits.KRW.max=2000000")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWhenFeeRateIsOutOfRange() {
        contextRunner.withPropertyValues("exchange.quote.fee-rate=1.5")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWhenLimitHasTooManyDigits() {
        contextRunner.withPropertyValues("exchange.quote.limits.JPY.min=100.5")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}

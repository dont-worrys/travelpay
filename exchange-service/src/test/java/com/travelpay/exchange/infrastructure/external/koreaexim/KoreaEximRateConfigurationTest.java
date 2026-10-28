package com.travelpay.exchange.infrastructure.external.koreaexim;

import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class KoreaEximRateConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(KoreaEximRateConfiguration.class));

    @Test
    void registersProviderAndPoliciesInKoreaEximMode() {
        contextRunner
                .withPropertyValues("exchange.rate.mode=koreaexim", "exchange.rate.koreaexim.api-key=test-key")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(KoreaEximRateProvider.class, context.getBean(ExchangeRateProvider.class));
                    assertInstanceOf(KoreaEximCollectionPolicy.class, context.getBean(RateCollectionPolicy.class));
                    assertInstanceOf(KoreaEximFreshnessPolicy.class, context.getBean(RateFreshnessPolicy.class));
                });
    }

    @Test
    void registersNothingInOtherMode() {
        contextRunner
                .withPropertyValues("exchange.rate.mode=mock")
                .run(context -> assertFalse(context.containsBean("koreaEximRateProvider")));
    }

    @Test
    void failsToStartWithoutApiKey() {
        contextRunner
                .withPropertyValues("exchange.rate.mode=koreaexim", "exchange.rate.koreaexim.api-key=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}

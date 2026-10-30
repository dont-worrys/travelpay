package com.travelpay.exchange.infrastructure.external.koreaexim;

import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/** 수집 모드 설정이 없으면 Provider가 등록되지 않아, 이를 쓰는 서비스가 기동 단계에서 실패하는지 확인합니다. */
class RateModeSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(KoreaEximRateConfiguration.class))
            .withBean(ExchangeRateRepository.class, () -> mock(ExchangeRateRepository.class))
            .withBean(ExchangeRateQueryService.class);

    @Test
    void startsWhenModeIsConfigured() {
        contextRunner
                .withPropertyValues("exchange.rate.mode=koreaexim", "exchange.rate.koreaexim.api-key=test-key")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void failsToStartWhenModeIsMissing() {
        contextRunner.run(context -> {
            assertNotNull(context.getStartupFailure());
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertInstanceOf(NoSuchBeanDefinitionException.class, root);
        });
    }
}

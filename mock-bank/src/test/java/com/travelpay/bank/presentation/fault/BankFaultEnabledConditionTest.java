package com.travelpay.bank.presentation.fault;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * mock-bank.fault.enabled 조건: true일 때만 장애 주입 필터·유실 비율 설정 빈을 만든다.
 * 키가 없거나 true가 아니면 빈이 없고 유실 비율 범위도 검증하지 않는다. Docker 불필요.
 */
class BankFaultEnabledConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void enabled_키가_없으면_빈이_없고_범위_밖_비율도_기동한다() {
        runner.withPropertyValues("mock-bank.fault.withdrawal-lost-rate=5")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFaultBeans(context, 0);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "", "yes"})
    void enabled가_true가_아니면_빈이_없다(String enabled) {
        runner.withPropertyValues("mock-bank.fault.enabled=" + enabled, "mock-bank.fault.withdrawal-lost-rate=5")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFaultBeans(context, 0);
                });
    }

    @Test
    void enabled가_true면_빈을_만든다() {
        runner.withPropertyValues("mock-bank.fault.enabled=true", "mock-bank.fault.withdrawal-lost-rate=0.5")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFaultBeans(context, 1);
                });
    }

    @Test
    void enabled가_true면_범위_밖_비율은_기동에_실패한다() {
        runner.withPropertyValues("mock-bank.fault.enabled=true", "mock-bank.fault.withdrawal-lost-rate=5")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    while (failure != null && !(failure instanceof BindValidationException)) {
                        failure = failure.getCause();
                    }
                    assertNotNull(failure);
                });
    }

    private static void assertFaultBeans(org.springframework.context.ApplicationContext context, int expected) {
        assertEquals(expected, context.getBeansOfType(BankFaultInjectionFilter.class).size());
        assertEquals(expected, context.getBeansOfType(BankWithdrawalLostRateFilter.class).size());
        assertEquals(expected, context.getBeansOfType(BankWithdrawalLostRateProperties.class).size());
    }

    /** 앱과 같게 등록한다: 필터는 @Import, 설정은 @ConfigurationPropertiesScan(둘 다 클래스의 @Conditional을 평가함). */
    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = BankFaultProperties.class)
    @Import({BankFaultInjectionFilter.class, BankWithdrawalLostRateFilter.class})
    static class Config {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}

package com.travelpay.bank.presentation.fault;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 유실 비율 범위 검증(기동 실패)과 필터 적용 대상(비율 0·헤더·경로)을 확인한다. Docker 불필요. */
class BankWithdrawalLostRatePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "1.1"})
    void 범위_밖이면_기동에_실패한다(String rate) {
        runner.withPropertyValues("mock-bank.fault.withdrawal-lost-rate=" + rate)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.5", "1.0"})
    void 범위_안이면_기동한다(String rate) {
        runner.withPropertyValues("mock-bank.fault.withdrawal-lost-rate=" + rate)
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void 비율_0이면_출금_요청도_필터를_건너뛴다() {
        assertTrue(filter(0.0).shouldNotFilter(withdrawal()));
    }

    @Test
    void 비율이_있으면_헤더_없는_출금_POST만_판정한다() {
        BankWithdrawalLostRateFilter filter = filter(1.0);
        MockHttpServletRequest withHeader = withdrawal();
        withHeader.addHeader(BankFaultInjectionFilter.HEADER, "DELAY");

        assertFalse(filter.shouldNotFilter(withdrawal()));
        assertTrue(filter.shouldNotFilter(withHeader));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/internal/v1/bank/refunds")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/internal/v1/bank/transactions/x")));
    }

    private static BankWithdrawalLostRateFilter filter(double rate) {
        return new BankWithdrawalLostRateFilter(new BankWithdrawalLostRateProperties(rate),
                JsonMapper.builder().build(), new SimpleMeterRegistry());
    }

    private static MockHttpServletRequest withdrawal() {
        return new MockHttpServletRequest("POST", BankWithdrawalLostRateFilter.WITHDRAWALS_PATH);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BankWithdrawalLostRateProperties.class)
    static class Config {
    }
}

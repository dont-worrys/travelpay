package com.travelpay.wallet.infra.client.bank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 설정 해석과 기동 실패 조건 */
class BankFaultInjectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BankFaultInjection.class);

    @Test
    void 설정이_없으면_꺼진_상태로_기동() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertNull(context.getBean(BankFaultInjection.class).withdrawHeader(UUID.randomUUID(), 0));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void 공백이면_꺼진_상태로_기동(String mode) {
        runner.withPropertyValues(BankFaultInjection.MODE_KEY + "=" + mode).run(context -> {
            assertThat(context).hasNotFailed();
            assertNull(context.getBean(BankFaultInjection.class).withdrawHeader(UUID.randomUUID(), 0));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost", " DELAY ", "Delay"})
    void 소문자와_앞뒤_공백도_인식(String mode) {
        runner.withPropertyValues(BankFaultInjection.MODE_KEY + "=" + mode).run(context -> {
            assertThat(context).hasNotFailed();
            assertEquals(mode.strip().toUpperCase(),
                    context.getBean(BankFaultInjection.class).withdrawHeader(UUID.randomUUID(), 0));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "LOST,DELAY", "on"})
    void 잘못된_값이면_기동_실패(String mode) {
        runner.withPropertyValues(BankFaultInjection.MODE_KEY + "=" + mode).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(BankFaultInjection.MODE_KEY);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOST", "DELAY"})
    void prod_프로필에서_값이_있으면_기동_실패(String mode) {
        runner.withPropertyValues("spring.profiles.active=prod", BankFaultInjection.MODE_KEY + "=" + mode)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("prod");
                });
    }

    @Test
    void prod_프로필이어도_값이_비었으면_기동() {
        runner.withPropertyValues("spring.profiles.active=prod", BankFaultInjection.MODE_KEY + "= ")
                .run(context -> assertThat(context).hasNotFailed());
    }
}

package com.travelpay.wallet.application.charge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 복구 스케줄러 설정 검증. DB 없이 스케줄러 빈만 올린다 */
class ChargeRecoverySchedulerConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ChargeRecoverySteps.class, () -> mock(ChargeRecoverySteps.class))
            .withBean(ChargeSagaExecutor.class, () -> mock(ChargeSagaExecutor.class))
            .withUserConfiguration(ChargeRecoveryScheduler.class);

    @Test
    void 기본값이면_기동된다() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ChargeRecoveryScheduler.class));
    }

    @Test
    void batch_size가_0이면_기동에_실패한다() {
        assertStartupFails("travelpay.wallet.charge.recovery.batch-size=0",
                "travelpay.wallet.charge.recovery.batch-size", ": 0");
    }

    @Test
    void batch_size가_음수면_기동에_실패한다() {
        assertStartupFails("travelpay.wallet.charge.recovery.batch-size=-1",
                "travelpay.wallet.charge.recovery.batch-size", ": -1");
    }

    @Test
    void fixed_delay가_0이면_기동에_실패한다() {
        assertStartupFails("travelpay.wallet.charge.recovery.fixed-delay=0s",
                "travelpay.wallet.charge.recovery.fixed-delay", ": 0s");
    }

    private void assertStartupFails(String property, String key, String value) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining(value);
        });
    }
}

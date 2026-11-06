package com.travelpay.wallet.application.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 대사 설정 검증. DB 없이 설정 빈만 올린다 */
class ReconciliationSettingsConfigTest {

    private static final String PREFIX = "travelpay.wallet.charge.reconciliation.";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(PREFIX + "initial-from=2026-11-01T00:00:00Z")
            .withUserConfiguration(ReconciliationSettings.class);

    @Test
    void 기본_조합이면_기동된다() {
        contextRunner.withPropertyValues(PREFIX + "overlap=10m", PREFIX + "max-period=1d")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ReconciliationSettings.class));
    }

    @Test
    void overlap이_max_period와_같으면_기동에_실패한다() {
        assertStartupFails("1d", "1d");
    }

    @Test
    void overlap이_max_period보다_크면_기동에_실패한다() {
        assertStartupFails("2d", "1d");
    }

    private void assertStartupFails(String overlap, String maxPeriod) {
        contextRunner.withPropertyValues(PREFIX + "overlap=" + overlap, PREFIX + "max-period=" + maxPeriod)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("overlap은 max-period보다 작아야 합니다")
                            .hasMessageContaining("overlap=" + overlap)
                            .hasMessageContaining("max-period=" + maxPeriod);
                });
    }
}

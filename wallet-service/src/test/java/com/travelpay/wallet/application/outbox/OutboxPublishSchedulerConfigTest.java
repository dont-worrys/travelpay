package com.travelpay.wallet.application.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Outbox 발행 스케줄러 설정 검증. DB·Kafka 없이 스케줄러 빈만 올린다 */
class OutboxPublishSchedulerConfigTest {

    private static final String BATCH_SIZE_KEY = "travelpay.wallet.outbox.publisher.batch-size";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(OutboxPublisher.class, () -> mock(OutboxPublisher.class))
            .withUserConfiguration(OutboxPublishScheduler.class);

    @Test
    void 기본값이면_500건으로_기동된다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OutboxPublishScheduler.class);
            assertPublishesWith(context.getBean(OutboxPublishScheduler.class), context.getBean(OutboxPublisher.class), 500);
        });
    }

    @Test
    void batch_size가_200이면_기동된다() {
        contextRunner.withPropertyValues(BATCH_SIZE_KEY + "=200").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OutboxPublishScheduler.class);
            assertPublishesWith(context.getBean(OutboxPublishScheduler.class), context.getBean(OutboxPublisher.class), 200);
        });
    }

    @Test
    void batch_size가_0이면_기동에_실패한다() {
        assertStartupFails(BATCH_SIZE_KEY + "=0", ": 0");
    }

    @Test
    void batch_size가_음수면_기동에_실패한다() {
        assertStartupFails(BATCH_SIZE_KEY + "=-1", ": -1");
    }

    private void assertPublishesWith(OutboxPublishScheduler scheduler, OutboxPublisher publisher, int batchSize) {
        scheduler.publish();
        verify(publisher, atLeastOnce()).publishPending(eq(batchSize), anyList());
    }

    private void assertStartupFails(String property, String value) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BATCH_SIZE_KEY)
                    .hasMessageContaining(value);
        });
    }
}

package com.travelpay.wallet.infra.kafka;

import com.travelpay.wallet.application.charge.ChargeSagaExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 충전 SAGA 컨슈머 동시성 설정이 리스너 컨테이너에 반영되는지 확인.
 * 컨슈머 자동 시작을 꺼서(consumer.enabled=false) Kafka 브로커·DB 없이 컨텍스트만 띄운다.
 */
class ChargeSagaConcurrencyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(ChargeSagaKafkaConfig.class, ChargeSagaConsumer.class)
            .withBean(ChargeSagaExecutor.class, () -> mock(ChargeSagaExecutor.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("travelpay.wallet.charge.saga.consumer.enabled=false");

    @Test
    void 동시성_미설정이면_1() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(concurrencyOf(context.getBean(KafkaListenerEndpointRegistry.class))).isEqualTo(1);
        });
    }

    @Test
    void 설정한_동시성이_리스너_컨테이너에_반영된다() {
        runner.withPropertyValues("travelpay.wallet.charge.saga.consumer.concurrency=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(concurrencyOf(context.getBean(KafkaListenerEndpointRegistry.class))).isEqualTo(3);
                });
    }

    @Test
    void 동시성이_1_미만이면_기동_실패() {
        runner.withPropertyValues("travelpay.wallet.charge.saga.consumer.concurrency=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("concurrency");
                });
    }

    private static int concurrencyOf(KafkaListenerEndpointRegistry registry) {
        MessageListenerContainer container = registry.getListenerContainer(ChargeSagaConsumer.GROUP_ID);
        assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);
        assertThat(container.isRunning()).isFalse();
        return ((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency();
    }
}

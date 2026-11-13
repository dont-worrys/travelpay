package com.travelpay.wallet.infra.kafka;

import com.travelpay.wallet.domain.charge.IllegalChargeTransitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 충전 SAGA 컨슈머 컨테이너. 동시성은 travelpay.wallet.charge.saga.consumer.concurrency (기본 3), 레코드 단위 커밋.
 * 충전당 메시지는 접수 때 1건이고 단계 전이는 이전 상태 조건부라, 동시 처리해도 같은 충전이 두 번 진행되지 않는다.
 * 결과 모름은 실행기가 예외 없이 끝내므로 여기까지 오는 예외는 DB 장애 등 인프라 예외다.
 * 그런 예외만 1초 간격 2회 다시 실행하고, 그래도 실패하면 ERROR를 남기고 넘긴다 (멈춘 건은 복구 스케줄러가 처리).
 * 메시지 형식 이상(InvalidChargeEventException)·허용되지 않은 전이(코드 버그)는 다시 실행해도 같으므로 바로 넘긴다.
 */
@Slf4j
@Configuration
public class ChargeSagaKafkaConfig {

    public static final String CONTAINER_FACTORY = "chargeSagaListenerContainerFactory";

    static final long RETRY_INTERVAL_MS = 1000L;
    static final long RETRY_ATTEMPTS = 2L;

    @Bean(CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> chargeSagaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            @Value("${travelpay.wallet.charge.saga.consumer.enabled:true}") boolean enabled,
            @Value("${travelpay.wallet.charge.saga.consumer.concurrency:3}") int concurrency) {
        if (concurrency < 1) {
            throw new IllegalStateException(
                    "travelpay.wallet.charge.saga.consumer.concurrency는 1 이상이어야 함 value=" + concurrency);
        }
        if (concurrency > WalletKafkaTopicConfig.CHARGE_REQUESTED_PARTITIONS) {
            // 코드에 선언한 파티션 수와만 비교 (브로커 조회 안 함). 파티션보다 많은 컨슈머는 할당을 못 받아 유휴
            log.warn("충전 SAGA 컨슈머 동시성이 토픽 파티션 수보다 큼 concurrency={} topic={} partitions={}",
                    concurrency, WalletKafkaTopicConfig.CHARGE_REQUESTED,
                    WalletKafkaTopicConfig.CHARGE_REQUESTED_PARTITIONS);
        }
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setAutoStartup(enabled);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(chargeSagaErrorHandler());
        return factory;
    }

    private static DefaultErrorHandler chargeSagaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> log.error("충전 SAGA 이벤트 처리 포기 topic={} partition={} offset={} chargeId={} "
                                + "exception={}", record.topic(), record.partition(), record.offset(), record.key(),
                        NestedExceptionUtils.getMostSpecificCause(exception).getClass().getName()),
                new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidChargeEventException.class, IllegalChargeTransitionException.class);
        return handler;
    }
}

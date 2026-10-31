package com.travelpay.wallet.infra.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 지갑이 발행하는 토픽. 브로커 자동 생성이 꺼져 있어 여기서 선언한다 */
@Configuration
public class WalletKafkaTopicConfig {

    public static final String CHARGE_REQUESTED = "wallet.charge.requested";

    @Bean
    public NewTopic chargeRequestedTopic() {
        return TopicBuilder.name(CHARGE_REQUESTED)
                .partitions(3)
                .replicas(1)    // 로컬 브로커 1대
                .build();
    }
}

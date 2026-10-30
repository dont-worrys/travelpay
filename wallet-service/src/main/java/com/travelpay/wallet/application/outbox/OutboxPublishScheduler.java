package com.travelpay.wallet.application.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 미발행 Outbox를 주기적으로 발행 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "travelpay.wallet.outbox.publisher", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxPublishScheduler {

    private final OutboxPublisher publisher;
    private final int batchSize;

    public OutboxPublishScheduler(OutboxPublisher publisher,
                                  @Value("${travelpay.wallet.outbox.publisher.batch-size:50}") int batchSize) {
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${travelpay.wallet.outbox.publisher.fixed-delay:1s}")
    public void publish() {
        try {
            publisher.publishPending(batchSize);
        } catch (RuntimeException e) {
            // 롤백되어 모두 PENDING으로 남고 다음 주기에 다시 보낸다
            log.warn("Outbox 발행 트랜잭션 실패 exception={}", e.getClass().getName());
        }
    }
}

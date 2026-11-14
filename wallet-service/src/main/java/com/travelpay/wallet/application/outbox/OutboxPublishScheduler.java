package com.travelpay.wallet.application.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 미발행 Outbox를 주기적으로 발행 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "travelpay.wallet.outbox.publisher", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxPublishScheduler {

    private static final String BATCH_SIZE_KEY = "travelpay.wallet.outbox.publisher.batch-size";

    private final OutboxPublisher publisher;
    private final int batchSize;

    /** 설정이 잘못되면 IllegalStateException으로 기동을 멈춘다 (@Scheduled 등록 전) */
    public OutboxPublishScheduler(OutboxPublisher publisher,
                                  @Value("${" + BATCH_SIZE_KEY + ":500}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalStateException(BATCH_SIZE_KEY + "는 1 이상이어야 합니다: " + batchSize);
        }
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${travelpay.wallet.outbox.publisher.fixed-delay:1s}")
    public void publish() {
        List<UUID> picked = new ArrayList<>();
        try {
            publisher.publishPending(batchSize, picked);
        } catch (RuntimeException e) {
            // 롤백되어 모두 PENDING으로 남고 다음 주기에 다시 보낸다
            log.warn("Outbox 발행 트랜잭션 실패 batchSize={} exception={}", picked.size(), e.getClass().getName());
            if (!picked.isEmpty()) {
                compensate(picked);
            }
        }
    }

    /** 롤백으로 사라진 retry_count 증가를 새 트랜잭션에서 다시 한다. 실패해도 다음 주기는 지금과 같이 진행 */
    private void compensate(List<UUID> picked) {
        try {
            int updated = publisher.increaseRetryCountIfPending(picked);
            log.warn("Outbox 롤백 batch 보정 batchSize={} updated={}", picked.size(), updated);
        } catch (RuntimeException e) {
            log.warn("Outbox 롤백 batch 보정 실패 batchSize={} exception={}", picked.size(), e.getClass().getName());
        }
    }
}

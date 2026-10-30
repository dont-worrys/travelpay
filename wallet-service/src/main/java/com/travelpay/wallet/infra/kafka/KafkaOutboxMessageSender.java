package com.travelpay.wallet.infra.kafka;

import com.travelpay.wallet.application.outbox.OutboxMessageSender;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Outbox event_type을 그대로 토픽 이름으로 쓴다. 선언된 토픽이 아니면 보내지 않는다 */
@Component
@RequiredArgsConstructor
public class KafkaOutboxMessageSender implements OutboxMessageSender {

    public static final String EVENT_ID_HEADER = "eventId";

    private static final Set<String> TOPICS = Set.of(WalletKafkaTopicConfig.CHARGE_REQUESTED);

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public CompletableFuture<Void> send(String eventType, UUID key, UUID eventId, String payload) {
        if (!TOPICS.contains(eventType)) {
            throw new IllegalArgumentException("발행할 토픽이 없는 이벤트 타입입니다.");
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(eventType, key.toString(), payload);
        record.headers().add(EVENT_ID_HEADER, eventId.toString().getBytes(StandardCharsets.UTF_8));
        return kafkaTemplate.send(record).thenApply(result -> null);
    }
}

package com.travelpay.wallet.infra.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.wallet.application.charge.ChargeSagaExecutor;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * wallet.charge.requested 수신 → payload의 chargeId로 SAGA 실행.
 * 같은 이벤트를 다시 받아도 실행기가 충전 상태를 보고 판단하므로 (종료 상태면 아무것도 안 함) 따로 중복 제거하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChargeSagaConsumer {

    static final String GROUP_ID = "wallet-charge-saga";

    /** charge_status_history.trace_id 길이 */
    private static final int TRACE_ID_MAX = 64;

    private final ChargeSagaExecutor executor;
    private final JsonMapper jsonMapper;

    @KafkaListener(id = GROUP_ID, groupId = GROUP_ID, topics = WalletKafkaTopicConfig.CHARGE_REQUESTED,
            containerFactory = ChargeSagaKafkaConfig.CONTAINER_FACTORY, concurrency = "1")
    public void onChargeRequested(ConsumerRecord<String, String> record) {
        executor.execute(chargeId(record.value()), eventId(record));
    }

    private UUID chargeId(String payload) {
        ChargeEvent event;
        try {
            event = jsonMapper.readValue(payload, ChargeEvent.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new InvalidChargeEventException(e);
        }
        if (event == null || event.chargeId() == null) {
            throw new InvalidChargeEventException(null);
        }
        return event.chargeId();
    }

    private static String eventId(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaOutboxMessageSender.EVENT_ID_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        String eventId = new String(header.value(), StandardCharsets.UTF_8);
        // 상태 이력 trace_id 길이를 넘는 값은 남기지 않는다 (발행기는 UUID만 보냄)
        return eventId.length() <= TRACE_ID_MAX ? eventId : null;
    }

    /** payload에서 chargeId만 읽는다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChargeEvent(UUID chargeId) {
    }
}

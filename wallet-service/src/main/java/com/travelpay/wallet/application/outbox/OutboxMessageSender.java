package com.travelpay.wallet.application.outbox;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Outbox 이벤트를 메시지 브로커로 보낸다. 응답은 future로 돌려주고 기다리지 않는다 */
public interface OutboxMessageSender {

    /**
     * @param eventType 보낼 대상(토픽)을 정하는 이벤트 타입
     * @param key       파티션 키 (aggregate id)
     * @param eventId   컨슈머 중복 제거용 id (outbox id)
     */
    CompletableFuture<Void> send(String eventType, UUID key, UUID eventId, String payload);
}

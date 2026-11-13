package com.travelpay.wallet.application.outbox;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Outbox 이벤트를 메시지 브로커로 보낸다. 응답은 future로 돌려주고 기다리지 않는다 */
public interface OutboxMessageSender {

    /**
     * @param eventType 보낼 대상(토픽)을 정하는 이벤트 타입
     * @param key       파티션 키 (aggregate id)
     * @param eventId   이벤트 식별자. 컨슈머는 이 값으로 중복을 제거하지 않고(실행기가 현재 상태로 판단), 상태 이력 trace_id에 남긴다.
     */
    CompletableFuture<Void> send(String eventType, UUID key, UUID eventId, String payload);
}

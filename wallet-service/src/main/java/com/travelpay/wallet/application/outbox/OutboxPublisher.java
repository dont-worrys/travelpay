package com.travelpay.wallet.application.outbox;

import com.travelpay.wallet.domain.outbox.OutboxEvent;
import com.travelpay.wallet.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 미발행 Outbox를 잠근 채 보내고, 응답에 따라 같은 트랜잭션에서 상태를 바꾼다.
 * 상태 변경이 실패해 롤백되면 이미 보낸 건도 PENDING으로 남아 다시 보낸다.
 * 컨슈머(ChargeSagaConsumer)는 eventId로 중복 제거하지 않고 trace_id 기록에만 쓴다. 같은 이벤트를 두 번 받아도
 * 실행기가 현재 충전 상태를 보고 판단하고(종료 상태면 아무것도 안 함), 상태 전이는 이전 상태 조건부라 먼저 진행된 건은 0행으로 멈춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    static final int TX_TIMEOUT_SECONDS = 10;
    // Kafka가 꺼져 있으면 send() 하나가 max.block(3초)까지 멈추므로, 이 시간이 지나면 남은 건은 다음 주기로 미룸
    private static final Duration SEND_BUDGET = Duration.ofSeconds(3);
    // delivery.timeout(5초) 응답을 기다리되, 트랜잭션(10초) 안에 상태 변경까지 끝나도록 시작 후 8초까지만
    private static final Duration RESPONSE_DEADLINE = Duration.ofSeconds(8);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessageSender messageSender;

    /** @return 발행에 성공한 건수 */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public int publishPending(int batchSize) {
        return publish(batchSize, null);
    }

    /**
     * publishPending(int)과 같고, 잠가 가져온 행의 id를 picked에 담는다.
     * 트랜잭션이 롤백되어 예외가 나도 호출자가 picked로 그 batch를 보정할 수 있게 한다.
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public int publishPending(int batchSize, List<UUID> picked) {
        return publish(batchSize, picked);
    }

    /**
     * 롤백된 batch 중 아직 PENDING인 행의 retry_count를 1 올린다. 다른 발행기가 잠근 행은 건너뛴다.
     * 롤백되면 retry_count 증가도 사라져 다음 주기에 같은 batch가 맨 앞에서 다시 선택되는 것을 완화한다.
     * @return 변경 건수
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public int increaseRetryCountIfPending(List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return outboxEventRepository.increaseRetryCountIfPending(ids);
    }

    private int publish(int batchSize, List<UUID> picked) {
        Instant startedAt = Instant.now();
        List<OutboxEvent> events = outboxEventRepository.findPendingForUpdate(batchSize);
        if (events.isEmpty()) {
            return 0;
        }
        if (picked != null) {
            for (OutboxEvent event : events) {
                picked.add(event.getId());
            }
        }

        Instant sendDeadline = startedAt.plus(SEND_BUDGET);
        List<CompletableFuture<Void>> futures = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            futures.add(send(event, sendDeadline));
        }

        Instant responseDeadline = startedAt.plus(RESPONSE_DEADLINE);
        int published = 0;
        for (int i = 0; i < events.size(); i++) {
            OutboxEvent event = events.get(i);
            if (await(event, futures.get(i), responseDeadline)) {
                event.markPublished();
                published++;
            } else {
                event.increaseRetryCount();
            }
        }
        return published;
    }

    private CompletableFuture<Void> send(OutboxEvent event, Instant sendDeadline) {
        if (Instant.now().isAfter(sendDeadline)) {
            return CompletableFuture.failedFuture(new TimeoutException("보내기 시간을 넘겨 다음 주기로 미룹니다."));
        }
        try {
            return messageSender.send(event.getEventType(), event.getAggregateId(), event.getId(), event.getPayload());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private boolean await(OutboxEvent event, CompletableFuture<Void> future, Instant deadline) {
        try {
            long remainingMillis = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            future.get(remainingMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logFailure(event, e);
        } catch (ExecutionException e) {
            logFailure(event, NestedExceptionUtils.getMostSpecificCause(e));
        } catch (TimeoutException e) {
            logFailure(event, e);
        }
        return false;
    }

    private void logFailure(OutboxEvent event, Throwable cause) {
        log.warn("Outbox 발행 실패 eventId={} aggregateId={} exception={}",
                event.getId(), event.getAggregateId(), cause.getClass().getName());
    }
}

package com.travelpay.wallet.application.outbox;

import com.travelpay.wallet.domain.outbox.OutboxEvent;
import com.travelpay.wallet.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 미발행 Outbox를 잠근 채 보내고, 응답에 따라 같은 트랜잭션에서 상태를 바꾼다.
 * 상태 변경이 실패해 롤백되면 이미 보낸 건도 PENDING으로 남아 다시 보낸다 (컨슈머는 eventId로 중복 제거).
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
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public int publishPending(int batchSize) {
        Instant startedAt = Instant.now();
        List<OutboxEvent> events = outboxEventRepository.findPendingForUpdate(batchSize);
        if (events.isEmpty()) {
            return 0;
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

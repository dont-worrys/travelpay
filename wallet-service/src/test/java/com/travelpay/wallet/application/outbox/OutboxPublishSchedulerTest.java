package com.travelpay.wallet.application.outbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 발행 스케줄러 한 주기: 롤백 시 보정 호출 여부. DB·Kafka 없이 발행기를 대역으로 둔다 */
class OutboxPublishSchedulerTest {

    private static final int BATCH_SIZE = 2;

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final OutboxPublishScheduler scheduler = new OutboxPublishScheduler(publisher, BATCH_SIZE);

    @Test
    void 발행이_성공하면_보정을_호출하지_않는다() {
        doAnswer(inv -> {
            inv.<List<UUID>>getArgument(1).add(UUID.randomUUID());
            return 1;
        }).when(publisher).publishPending(anyInt(), anyList());

        scheduler.publish();

        verify(publisher, never()).increaseRetryCountIfPending(anyList());
    }

    @Test
    void 롤백되면_선택했던_id로_보정한다() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        doAnswer(inv -> {
            inv.<List<UUID>>getArgument(1).addAll(List.of(first, second));
            throw new IllegalStateException("롤백 (테스트)");
        }).when(publisher).publishPending(anyInt(), anyList());

        scheduler.publish();

        verify(publisher).increaseRetryCountIfPending(List.of(first, second));
    }

    @Test
    void 선택한_행이_없이_실패하면_보정하지_않는다() {
        when(publisher.publishPending(anyInt(), anyList())).thenThrow(new IllegalStateException("조회 실패 (테스트)"));

        scheduler.publish();

        verify(publisher, never()).increaseRetryCountIfPending(anyList());
    }

    @Test
    void 보정이_예외를_던져도_밖으로_던지지_않는다() {
        doAnswer(inv -> {
            inv.<List<UUID>>getArgument(1).add(UUID.randomUUID());
            throw new IllegalStateException("롤백 (테스트)");
        }).when(publisher).publishPending(anyInt(), anyList());
        when(publisher.increaseRetryCountIfPending(anyList())).thenThrow(new IllegalStateException("보정 실패 (테스트)"));

        assertThatCode(scheduler::publish).doesNotThrowAnyException();
        verify(publisher).increaseRetryCountIfPending(anyList());
    }
}

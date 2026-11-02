package com.travelpay.payment.transfer.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferRecoverySchedulerTest {

    private TransferService transferService;
    private SimpleMeterRegistry meterRegistry;
    private TransferRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        transferService = mock(TransferService.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new TransferRecoveryScheduler(transferService, meterRegistry);
        when(transferService.findStuckProcessing(any(), anyInt())).thenReturn(new StuckTransfers(0, List.of()));
    }

    /** 대기 시간이 지난 처리 중 송금을 최대 10건 복구하고, 30분 넘은 송금 10건을 알림 대상으로 조회합니다. */
    @Test
    void recoversDueTransfersAndReportsStuckOnes() {
        Instant start = Instant.now();

        scheduler.recover();

        Instant end = Instant.now();
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> stuckBefore = ArgumentCaptor.forClass(Instant.class);
        verify(transferService).recoverDue(now.capture(), eq(10));
        verify(transferService).findStuckProcessing(stuckBefore.capture(), eq(10));
        assertBetween(start, end, now.getValue());
        assertBetween(start.minus(Duration.ofMinutes(30)), end.minus(Duration.ofMinutes(30)), stuckBefore.getValue());
    }

    private static void assertBetween(Instant from, Instant to, Instant actual) {
        assertTrue(!actual.isBefore(from) && !actual.isAfter(to), () -> actual + " not in [" + from + ", " + to + "]");
    }

    @Test
    void publishesStuckAndManualReviewCountsAsGauges() {
        when(transferService.findStuckProcessing(any(), anyInt()))
                .thenReturn(new StuckTransfers(2, List.of(UUID.randomUUID(), UUID.randomUUID())))
                .thenReturn(new StuckTransfers(0, List.of()));
        when(transferService.countManualReview()).thenReturn(3L).thenReturn(1L);

        scheduler.recover();
        assertEquals(2.0, gauge("transfer.processing.stuck"));
        assertEquals(3.0, gauge("transfer.manual_review"));

        scheduler.recover();
        assertEquals(0.0, gauge("transfer.processing.stuck"));
        assertEquals(1.0, gauge("transfer.manual_review"));
    }

    /** 복구가 실패해도 알림 수치는 갱신하고, 예외를 밖으로 던지지 않습니다. */
    @Test
    void reportsCountsEvenWhenRecoveryFails() {
        when(transferService.recoverDue(any(), anyInt())).thenThrow(new IllegalStateException("db down"));
        when(transferService.findStuckProcessing(any(), anyInt()))
                .thenReturn(new StuckTransfers(1, List.of(UUID.randomUUID())));

        assertDoesNotThrow(scheduler::recover);

        assertEquals(1.0, gauge("transfer.processing.stuck"));
    }

    @Test
    void doesNotThrowWhenStuckReportFails() {
        when(transferService.findStuckProcessing(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(scheduler::recover);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }
}

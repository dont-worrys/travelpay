package com.travelpay.payment.presentation.payment;

import com.travelpay.payment.application.payment.ManualReviewLock;
import com.travelpay.payment.application.payment.ManualReviewReconciler;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.RecoveryBatchResult;
import com.travelpay.payment.application.payment.dto.StuckSettlementResult;
import com.travelpay.payment.application.merchant.SettlementService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PendingSettlementSchedulerTest {

    private PaymentService paymentService;
    private SettlementService settlementService;
    private ManualReviewReconciler reconciler;
    private SimpleMeterRegistry meterRegistry;
    private PendingSettlementScheduler scheduler;

    // 잠금을 잡은 경우(이 서버가 처리)와 다른 서버가 쥐고 있는 경우
    private static final ManualReviewLock RUNS = task -> {
        task.run();
        return true;
    };
    private static final ManualReviewLock HELD_ELSEWHERE = task -> false;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        settlementService = mock(SettlementService.class);
        reconciler = mock(ManualReviewReconciler.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new PendingSettlementScheduler(paymentService, settlementService, reconciler, RUNS,
                meterRegistry);
        when(paymentService.findStuckSettlements(any(), anyInt()))
                .thenReturn(new StuckSettlementResult(0, List.of(), 0, List.of()));
    }

    // 많이 쌓였을 때 이어서 처리

    private static final RecoveryBatchResult FULL = new RecoveryBatchResult(10, false);
    private static final RecoveryBatchResult PARTIAL = new RecoveryBatchResult(3, false);
    private static final RecoveryBatchResult LEDGER_DOWN = new RecoveryBatchResult(10, true);

    @Test
    void keepsRecoveringWhileBatchesAreFull() {
        when(paymentService.recoverPayments(any(), anyInt())).thenReturn(FULL, FULL, PARTIAL);
        when(paymentService.recoverRefunds(any(), anyInt())).thenReturn(PARTIAL);

        scheduler.recover();

        verify(paymentService, times(3)).recoverPayments(any(), eq(10));
        verify(paymentService, times(1)).recoverRefunds(any(), eq(10));
        verify(settlementService, times(1)).recoverSettlements(any(), eq(10));
    }

    @Test
    void stopsOnlyTheTypeWhoseLedgerLooksUnavailable() {
        when(paymentService.recoverPayments(any(), anyInt())).thenReturn(LEDGER_DOWN);
        when(paymentService.recoverRefunds(any(), anyInt())).thenReturn(FULL, PARTIAL);

        scheduler.recover();

        // 결과 모름이 연달아 나온 결제는 이번 주기에 더 보내지 않고, 환불은 이어서 처리
        verify(paymentService, times(1)).recoverPayments(any(), anyInt());
        verify(paymentService, times(2)).recoverRefunds(any(), anyInt());
    }

    @Test
    void takesTurnsBetweenPaymentsRefundsAndSettlements() {
        when(paymentService.recoverPayments(any(), anyInt())).thenReturn(FULL, PARTIAL);
        when(paymentService.recoverRefunds(any(), anyInt())).thenReturn(FULL, PARTIAL);
        when(settlementService.recoverSettlements(any(), anyInt())).thenReturn(FULL, PARTIAL);

        scheduler.recover();

        InOrder order = inOrder(paymentService, settlementService);
        order.verify(paymentService).recoverPayments(any(), anyInt());
        order.verify(paymentService).recoverRefunds(any(), anyInt());
        order.verify(settlementService).recoverSettlements(any(), anyInt());
        order.verify(paymentService).recoverPayments(any(), anyInt());
        order.verify(paymentService).recoverRefunds(any(), anyInt());
        order.verify(settlementService).recoverSettlements(any(), anyInt());
    }

    @Test
    void stopsWhenRoundBudgetIsUsedUp() {
        // 시작 0초 → 첫 바퀴 뒤 5초(계속) → 둘째 바퀴 뒤 9초(한도 8초 초과, 멈춤)
        Deque<Long> clock = new ArrayDeque<>(List.of(0L, 5_000_000_000L, 9_000_000_000L));
        PendingSettlementScheduler timed = new PendingSettlementScheduler(paymentService, settlementService,
                reconciler, RUNS, new SimpleMeterRegistry(), () -> clock.size() > 1 ? clock.poll() : clock.peek());
        when(paymentService.recoverPayments(any(), anyInt())).thenReturn(FULL);

        timed.recover();

        verify(paymentService, times(2)).recoverPayments(any(), anyInt());
    }

    @Test
    void recoversPaymentsAndRefundsWithBatchSize() {
        scheduler.recover();

        verify(paymentService).recoverPayments(any(), eq(10));
        verify(paymentService).recoverRefunds(any(), eq(10));
    }

    @Test
    void continuesWhenPaymentRecoveryThrows() {
        doThrow(new IllegalStateException("DB 연결 실패")).when(paymentService).recoverPayments(any(), anyInt());
        when(paymentService.countManualReviewPayments()).thenReturn(2L);

        scheduler.recover();

        // 결제 복구가 실패해도 환불 복구와 집계는 돈다
        verify(paymentService).recoverRefunds(any(), eq(10));
        verify(paymentService).findStuckSettlements(any(), eq(10));
        assertEquals(2.0, gauge("payment.settlement.manual_review", "payment"));
    }

    @Test
    void continuesWhenRefundRecoveryThrows() {
        doThrow(new IllegalStateException("DB 연결 실패")).when(paymentService).recoverRefunds(any(), anyInt());

        scheduler.recover();

        verify(paymentService).findStuckSettlements(any(), eq(10));
    }

    @Test
    void doesNotThrowWhenCountingFails() {
        when(paymentService.findStuckSettlements(any(), anyInt())).thenThrow(new IllegalStateException("DB 연결 실패"));

        scheduler.recover();

        verify(paymentService).recoverPayments(any(), eq(10));
    }

    @Test
    void publishesStuckCountsAsGauges() {
        when(paymentService.findStuckSettlements(any(), anyInt()))
                .thenReturn(new StuckSettlementResult(3, List.of(UUID.randomUUID()), 1, List.of(UUID.randomUUID())));

        scheduler.recover();

        assertEquals(3.0, gauge("payment.settlement.stuck", "payment"));
        assertEquals(1.0, gauge("payment.settlement.stuck", "refund"));

        verify(paymentService).findStuckSettlements(any(), eq(10));
    }

    @Test
    void resetsGaugesWhenNothingIsStuck() {
        when(paymentService.findStuckSettlements(any(), anyInt()))
                .thenReturn(new StuckSettlementResult(3, List.of(), 1, List.of()))
                .thenReturn(new StuckSettlementResult(0, List.of(), 0, List.of()));

        scheduler.recover();
        scheduler.recover();

        assertEquals(0.0, gauge("payment.settlement.stuck", "payment"));
        assertEquals(0.0, gauge("payment.settlement.stuck", "refund"));
    }

    @Test
    void publishesManualReviewCountsAsGauges() {
        when(paymentService.countManualReviewPayments()).thenReturn(2L);
        when(paymentService.countManualReviewRefunds()).thenReturn(1L);

        scheduler.recover();

        assertEquals(2.0, gauge("payment.settlement.manual_review", "payment"));
        assertEquals(1.0, gauge("payment.settlement.manual_review", "refund"));
    }

    @Test
    void publishesOverdueRefundRequestCountAsGauge() {
        when(paymentService.countOverdueRefundRequests()).thenReturn(3L);

        scheduler.recover();

        assertEquals(3.0, meterRegistry.get("payment.refund_request.overdue").gauge().value());
    }

    @Test
    void recoversSettlementsAfterRefunds() {
        scheduler.recover();

        verify(settlementService).recoverSettlements(any(), eq(10));
    }

    @Test
    void continuesWhenSettlementRecoveryThrows() {
        doThrow(new IllegalStateException("DB 연결 실패")).when(settlementService).recoverSettlements(any(), anyInt());
        when(settlementService.countStuckSettlements(any())).thenReturn(2L);

        scheduler.recover();

        // 정산 복구가 실패해도 집계는 돈다
        assertEquals(2.0, gauge("payment.settlement.stuck", "settlement"));
    }

    @Test
    void publishesStuckSettlementCountAndLogsSample() {
        when(settlementService.countStuckSettlements(any())).thenReturn(1L);
        when(settlementService.findStuckSettlementIds(any(), anyInt())).thenReturn(List.of(UUID.randomUUID()));

        scheduler.recover();

        assertEquals(1.0, gauge("payment.settlement.stuck", "settlement"));
        verify(settlementService).findStuckSettlementIds(any(), eq(10));
    }

    // MANUAL_REVIEW 자동 처리

    @Test
    void reconcilesManualReviewsEverySixCycles() {
        for (int i = 0; i < 7; i++) {
            scheduler.recover();
        }

        // 첫 주기와 일곱 번째 주기(6주기 뒤)에만
        verify(reconciler, times(2)).reconcile(any());
    }

    @Test
    void reconcilesAfterRecoveryAndBeforeRefreshingGauges() {
        scheduler.recover();

        InOrder order = inOrder(paymentService, settlementService, reconciler);
        order.verify(settlementService).recoverSettlements(any(), anyInt());
        order.verify(reconciler).reconcile(any());
        order.verify(paymentService).countManualReviewPayments();
    }

    @Test
    void skipsReconcileWhenAnotherServerHoldsTheLock() {
        SimpleMeterRegistry otherRegistry = new SimpleMeterRegistry();
        PendingSettlementScheduler other = new PendingSettlementScheduler(paymentService, settlementService,
                reconciler, HELD_ELSEWHERE, otherRegistry);
        when(paymentService.countManualReviewPayments()).thenReturn(2L);

        other.recover();

        // 자동 처리는 건너뛰고 복구·지표 갱신은 그대로
        verify(reconciler, never()).reconcile(any());
        verify(paymentService).recoverPayments(any(), eq(10));
        assertEquals(2.0, otherRegistry.get("payment.settlement.manual_review").tag("type", "payment").gauge().value());
    }

    @Test
    void refreshesGaugesWhenReconcilerThrows() {
        doThrow(new IllegalStateException("DB 연결 실패")).when(reconciler).reconcile(any());
        when(paymentService.countManualReviewPayments()).thenReturn(2L);

        scheduler.recover();

        assertEquals(2.0, gauge("payment.settlement.manual_review", "payment"));
    }

    private double gauge(String name, String type) {
        return meterRegistry.get(name).tag("type", type).gauge().value();
    }
}

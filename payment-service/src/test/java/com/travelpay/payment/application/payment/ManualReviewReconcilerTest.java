package com.travelpay.payment.application.payment;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.application.merchant.SettlementReviewService;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.RefundRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualReviewReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final Instant UPDATED_BEFORE = NOW.minus(Duration.ofMinutes(1));
    private static final Instant CURSOR_START = Instant.EPOCH;
    private static final UUID CURSOR_START_ID = new UUID(0L, 0L);

    private PaymentRepository paymentRepository;
    private RefundRepository refundRepository;
    private SettlementRepository settlementRepository;
    private PaymentReviewService paymentReviewService;
    private SettlementReviewService settlementReviewService;
    private SimpleMeterRegistry meterRegistry;
    private ManualReviewReconciler reconciler;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        refundRepository = mock(RefundRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        paymentReviewService = mock(PaymentReviewService.class);
        settlementReviewService = mock(SettlementReviewService.class);
        meterRegistry = new SimpleMeterRegistry();
        reconciler = reconciler(() -> 0L);
    }

    private ManualReviewReconciler reconciler(LongSupplier clock) {
        return new ManualReviewReconciler(paymentRepository, refundRepository, settlementRepository,
                paymentReviewService, settlementReviewService, meterRegistry, clock);
    }

    @Test
    void resolvesOnlyItemsTheServiceResolvesAndCountsThem() {
        List<Payment> payments = payments(3);
        when(paymentRepository.findManualReviewsAfter(any(), any(), any(), anyInt())).thenReturn(payments);
        when(paymentReviewService.autoResolvePayment(any()))
                .thenReturn(AutoResolveOutcome.RESOLVED, AutoResolveOutcome.SKIPPED,
                        AutoResolveOutcome.ALREADY_RESOLVED);

        int resolved = reconciler.reconcile(NOW);

        assertEquals(1, resolved);
        assertEquals(1.0, meterRegistry.get("payment.manual_review.auto_resolved").tag("type", "payment")
                .counter().count());
        // 환불·정산도 차례로 봄
        verify(refundRepository).findManualReviewsAfter(UPDATED_BEFORE, CURSOR_START, CURSOR_START_ID, 10);
        verify(settlementRepository).findManualReviewsAfter(UPDATED_BEFORE, CURSOR_START, CURSOR_START_ID, 10);
    }

    @Test
    void skipsItemsThatJustBecameManualReview() {
        reconciler.reconcile(NOW);

        // MANUAL_REVIEW로 바뀐 지 1분이 지난 건만
        verify(paymentRepository).findManualReviewsAfter(UPDATED_BEFORE, CURSOR_START, CURSOR_START_ID, 10);
    }

    @Test
    void continuesFromLastItemWhenBatchWasFull() {
        List<Payment> full = payments(10);
        when(paymentRepository.findManualReviewsAfter(any(), any(), any(), anyInt())).thenReturn(full, List.of());
        when(paymentReviewService.autoResolvePayment(any())).thenReturn(AutoResolveOutcome.SKIPPED);

        reconciler.reconcile(NOW);
        reconciler.reconcile(NOW);

        // 건너뛴 건이 앞에 쌓여도 다음에는 그 뒤부터 봄
        Payment last = full.get(9);
        verify(paymentRepository).findManualReviewsAfter(UPDATED_BEFORE, last.getCreatedAt(), last.getId(), 10);
    }

    @Test
    void startsOverWhenReachedTheEnd() {
        List<Payment> found = payments(3);
        when(paymentRepository.findManualReviewsAfter(any(), any(), any(), anyInt())).thenReturn(found);
        when(paymentReviewService.autoResolvePayment(any())).thenReturn(AutoResolveOutcome.SKIPPED);

        reconciler.reconcile(NOW);
        reconciler.reconcile(NOW);

        verify(paymentRepository, times(2))
                .findManualReviewsAfter(UPDATED_BEFORE, CURSOR_START, CURSOR_START_ID, 10);
    }

    @Test
    void stopsAfterThreeConsecutiveLedgerFailuresAndRetriesFromSameStart() {
        List<Payment> found = payments(10);
        when(paymentRepository.findManualReviewsAfter(any(), any(), any(), anyInt())).thenReturn(found);
        when(paymentReviewService.autoResolvePayment(any()))
                .thenThrow(new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE));

        reconciler.reconcile(NOW);

        verify(paymentReviewService, times(3)).autoResolvePayment(any());
        // 원장 장애로 보고 이번 차례는 환불·정산도 보지 않음
        verify(refundRepository, never()).findManualReviewsAfter(any(), any(), any(), anyInt());
        verify(settlementRepository, never()).findManualReviewsAfter(any(), any(), any(), anyInt());

        // 다음 결제 차례는 조회하지 못한 건을 건너뛰지 않도록 같은 시작 위치부터
        reconciler.reconcile(NOW);
        verify(paymentRepository, times(2))
                .findManualReviewsAfter(UPDATED_BEFORE, CURSOR_START, CURSOR_START_ID, 10);
    }

    @Test
    void keepsGoingWhenLedgerFailuresAreNotConsecutive() {
        List<Payment> found = payments(5);
        when(paymentRepository.findManualReviewsAfter(any(), any(), any(), anyInt())).thenReturn(found);
        BusinessException unavailable = new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE);
        when(paymentReviewService.autoResolvePayment(any()))
                .thenThrow(unavailable, unavailable)
                .thenReturn(AutoResolveOutcome.RESOLVED)
                .thenThrow(unavailable, unavailable);

        int resolved = reconciler.reconcile(NOW);

        assertEquals(1, resolved);
        verify(paymentReviewService, times(5)).autoResolvePayment(any());
        verify(refundRepository).findManualReviewsAfter(any(), any(), any(), anyInt());
    }

    @Test
    void keepsGoingWhenOneItemFailsForAnotherReason() {
        List<Payment> found = payments(2);
        when(paymentRepository.findManualReviewsAfter(any(), any(), any(), anyInt())).thenReturn(found);
        when(paymentReviewService.autoResolvePayment(any()))
                .thenThrow(new IllegalStateException("가맹점 원장 지갑을 찾을 수 없습니다."))
                .thenReturn(AutoResolveOutcome.RESOLVED);

        int resolved = reconciler.reconcile(NOW);

        assertEquals(1, resolved);
    }

    @Test
    void stopsWhenTimeBudgetIsUsedUp() {
        // 시작 0초 → 첫 건 전 1초 → 둘째 건 전 6초(한도 5초 초과, 멈춤)
        Deque<Long> clock = new ArrayDeque<>(List.of(0L, 1_000_000_000L, 6_000_000_000L));
        ManualReviewReconciler timed = reconciler(() -> clock.size() > 1 ? clock.poll() : clock.peek());
        List<Payment> payments = payments(10);
        when(paymentRepository.findManualReviewsAfter(any(), any(), any(), anyInt()))
                .thenReturn(payments, List.of());
        when(paymentReviewService.autoResolvePayment(any())).thenReturn(AutoResolveOutcome.SKIPPED);

        timed.reconcile(NOW);

        verify(paymentReviewService, times(1)).autoResolvePayment(any());
        verify(refundRepository, never()).findManualReviewsAfter(any(), any(), any(), anyInt());

        // 다음 결제 차례는 마지막으로 본 건 다음부터 이어서
        timed.reconcile(NOW);
        Payment first = payments.get(0);
        verify(paymentRepository).findManualReviewsAfter(UPDATED_BEFORE, first.getCreatedAt(), first.getId(), 10);
    }

    @Test
    void takesTurnsWhichTypeGoesFirst() {
        reconciler.reconcile(NOW);
        reconciler.reconcile(NOW);

        InOrder order = inOrder(paymentRepository, refundRepository, settlementRepository);
        order.verify(paymentRepository).findManualReviewsAfter(any(), any(), any(), anyInt());
        order.verify(refundRepository).findManualReviewsAfter(any(), any(), any(), anyInt());
        order.verify(settlementRepository).findManualReviewsAfter(any(), any(), any(), anyInt());
        // 두 번째는 환불부터
        order.verify(refundRepository).findManualReviewsAfter(any(), any(), any(), anyInt());
        order.verify(settlementRepository).findManualReviewsAfter(any(), any(), any(), anyInt());
        order.verify(paymentRepository).findManualReviewsAfter(any(), any(), any(), anyInt());
    }

    private static List<Payment> payments(int count) {
        List<Payment> payments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Payment payment = mock(Payment.class);
            when(payment.getId()).thenReturn(UUID.randomUUID());
            when(payment.getCreatedAt()).thenReturn(Instant.parse("2026-11-13T09:00:00Z").plusSeconds(i));
            payments.add(payment);
        }
        return payments;
    }
}

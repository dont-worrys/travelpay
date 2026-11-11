package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 복구 주기 이어서 처리·단계 묶음별 중단·멈춘 충전 지표. DB 없이 조회 단계와 실행기를 대역으로 둔다 */
class ChargeRecoverySchedulerTest {

    private static final int BATCH = 10;
    private static final Set<ChargeStatus> BANK = ChargeRecoveryGroup.BANK.statuses();
    private static final Set<ChargeStatus> LEDGER = ChargeRecoveryGroup.LEDGER.statuses();

    private final ChargeRecoverySteps steps = mock(ChargeRecoverySteps.class);
    private final ChargeSagaExecutor executor = mock(ChargeSagaExecutor.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ChargeRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(steps.findTargets(any(), anyInt())).thenReturn(List.of());
        when(steps.claim(any())).thenReturn(true);
        scheduler = new ChargeRecoveryScheduler(steps, executor, meterRegistry, BATCH, "10s", nanos::get);
    }

    @Test
    void 가득_찬_배치면_같은_주기에서_이어서_조회하고_덜_찬_배치에서_멈춘다() {
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenReturn(
                targets(ChargeStatus.BANK_PROCESSING, 10), targets(ChargeStatus.BANK_PROCESSING, 10),
                targets(ChargeStatus.BANK_PROCESSING, 3));
        when(executor.execute(any(), anyString())).thenReturn(answered(ChargeStatus.BANK_PROCESSING));

        scheduler.recover();

        verify(steps, times(3)).findTargets(eq(BANK), eq(BATCH));
        verify(steps, times(1)).findTargets(eq(LEDGER), eq(BATCH));
        verify(executor, times(23)).execute(any(), anyString());
    }

    @Test
    void 시간_한도가_지나면_새_배치를_시작하지_않지만_첫_바퀴는_모든_묶음을_처리한다() {
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenReturn(targets(ChargeStatus.BANK_PROCESSING, 10));
        when(steps.findTargets(eq(LEDGER), eq(BATCH))).thenReturn(targets(ChargeStatus.CREDIT_PROCESSING, 10));
        // 실행 한 건마다 1초가 지난다 → 은행 배치 하나로 8초 한도를 넘김
        when(executor.execute(any(), anyString())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
            return answered(ChargeStatus.BANK_PROCESSING);
        });

        scheduler.recover();

        verify(steps, times(1)).findTargets(eq(BANK), eq(BATCH));
        verify(steps, times(1)).findTargets(eq(LEDGER), eq(BATCH));
        verify(executor, times(20)).execute(any(), anyString());
    }

    @Test
    void 은행_결과_모름이_3번_연달아_나오면_은행_묶음만_멈추고_원장_묶음은_계속한다() {
        List<ChargeRecoveryTarget> bank = targets(ChargeStatus.BANK_PROCESSING, 10);
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenReturn(bank);
        when(steps.findTargets(eq(LEDGER), eq(BATCH))).thenReturn(
                targets(ChargeStatus.CREDIT_PROCESSING, 10), targets(ChargeStatus.CREDIT_PROCESSING, 2));
        when(executor.execute(any(), anyString())).thenAnswer(invocation ->
                isBank(invocation.getArgument(0), bank)
                        ? unknown(ChargeStatus.BANK_PROCESSING)
                        : answered(ChargeStatus.CREDIT_PROCESSING));

        scheduler.recover();

        verify(steps, times(1)).findTargets(eq(BANK), eq(BATCH));
        verify(steps, times(2)).findTargets(eq(LEDGER), eq(BATCH));
        for (int i = 0; i < 10; i++) {
            verify(executor, times(i < 3 ? 1 : 0)).execute(eq(bank.get(i).id()), anyString());
            verify(steps, times(i < 3 ? 1 : 0)).claim(bank.get(i));
        }
        verify(executor, times(15)).execute(any(), anyString());
    }

    @Test
    void 은행_묶음_대상이_원장_단계에서_결과_모름이면_원장_묶음으로_센다() {
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenReturn(
                targets(ChargeStatus.BANK_PROCESSING, 10), targets(ChargeStatus.BANK_PROCESSING, 1));
        when(steps.findTargets(eq(LEDGER), eq(BATCH))).thenReturn(targets(ChargeStatus.CREDIT_PROCESSING, 10));
        // 출금은 확실한 응답, 적립은 결과 모름
        when(executor.execute(any(), anyString())).thenReturn(
                new ChargeSagaOutcome(Set.of(ChargeStatus.BANK_PROCESSING), ChargeStatus.CREDIT_PROCESSING));

        scheduler.recover();

        // 은행 묶음은 멈추지 않고, 원장 묶음은 장애로 보아 조회하지 않는다
        verify(steps, times(2)).findTargets(eq(BANK), eq(BATCH));
        verify(steps, never()).findTargets(eq(LEDGER), anyInt());
        verify(executor, times(11)).execute(any(), anyString());
    }

    @Test
    void 확실한_응답이_끼면_연속_횟수가_처음부터_다시_센다() {
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenReturn(targets(ChargeStatus.BANK_PROCESSING, 6));
        when(executor.execute(any(), anyString())).thenReturn(
                unknown(ChargeStatus.BANK_PROCESSING), unknown(ChargeStatus.BANK_PROCESSING),
                answered(ChargeStatus.BANK_PROCESSING),
                unknown(ChargeStatus.BANK_PROCESSING), unknown(ChargeStatus.BANK_PROCESSING),
                unknown(ChargeStatus.BANK_PROCESSING));

        scheduler.recover();

        verify(executor, times(6)).execute(any(), anyString());
    }

    @Test
    void 선점_실패는_결과_모름으로_세지_않는다() {
        List<ChargeRecoveryTarget> bank = targets(ChargeStatus.BANK_PROCESSING, 6);
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenReturn(bank);
        when(steps.claim(any())).thenReturn(true, false, true, false, true, true);
        when(executor.execute(any(), anyString())).thenReturn(unknown(ChargeStatus.BANK_PROCESSING));

        scheduler.recover();

        verify(steps, times(5)).claim(any());
        verify(steps, never()).claim(bank.get(5));
        verify(executor, times(3)).execute(any(), anyString());
    }

    @Test
    void 실행기_예외는_대상_묶음의_결과_모름으로_센다() {
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenReturn(targets(ChargeStatus.REQUESTED, 10));
        when(executor.execute(any(), anyString())).thenThrow(new IllegalStateException("boom"));

        scheduler.recover();

        verify(executor, times(3)).execute(any(), anyString());
    }

    @Test
    void 한_묶음의_조회가_실패해도_다른_묶음과_지표는_처리한다() {
        when(steps.findTargets(eq(BANK), eq(BATCH))).thenThrow(new IllegalStateException("db"));
        when(steps.findTargets(eq(LEDGER), eq(BATCH))).thenReturn(targets(ChargeStatus.CREDIT_PROCESSING, 2));
        when(steps.countStuck(any())).thenReturn(4L);

        scheduler.recover();

        verify(executor, times(2)).execute(any(), anyString());
        assertEquals(4.0, stuckGauge());
    }

    @Test
    void 멈춘_충전_수는_30분_전_생성_시각_기준으로_매_주기_갱신하고_조회가_실패하면_이전_값을_유지한다() {
        when(steps.countStuck(any())).thenReturn(7L).thenThrow(new IllegalStateException("db"));
        ArgumentCaptor<Instant> createdBefore = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        scheduler.recover();
        assertEquals(7.0, stuckGauge());
        scheduler.recover();
        assertEquals(7.0, stuckGauge());

        verify(steps, times(2)).countStuck(createdBefore.capture());
        Instant cutoff = createdBefore.getAllValues().getFirst();
        assertTrue(!cutoff.isBefore(before.minus(Duration.ofMinutes(30)))
                && !cutoff.isAfter(Instant.now().minus(Duration.ofMinutes(30))));
    }

    private double stuckGauge() {
        return meterRegistry.get(ChargeRecoveryScheduler.STUCK_METRIC).gauge().value();
    }

    private static List<ChargeRecoveryTarget> targets(ChargeStatus status, int count) {
        List<ChargeRecoveryTarget> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            targets.add(new ChargeRecoveryTarget(UUID.randomUUID(), status, Instant.now()));
        }
        return targets;
    }

    private static boolean isBank(UUID chargeId, List<ChargeRecoveryTarget> bank) {
        return bank.stream().anyMatch(target -> target.id().equals(chargeId));
    }

    private static ChargeSagaOutcome answered(ChargeStatus status) {
        return new ChargeSagaOutcome(Set.of(status), null);
    }

    private static ChargeSagaOutcome unknown(ChargeStatus status) {
        return new ChargeSagaOutcome(Set.of(), status);
    }
}

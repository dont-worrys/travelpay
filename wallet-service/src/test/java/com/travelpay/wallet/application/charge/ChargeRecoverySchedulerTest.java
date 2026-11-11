package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 복구 주기 이어서 처리·의존성별(원장 묶음·은행 코드별) 중단·은행 제외·멈춘 충전 지표. DB 없이 조회 단계와 실행기를 대역으로 둔다 */
@Timeout(10)
class ChargeRecoverySchedulerTest {

    private static final int BATCH = 10;
    private static final Set<ChargeStatus> BANK = ChargeRecoveryGroup.BANK.statuses();
    private static final Set<ChargeStatus> LEDGER = ChargeRecoveryGroup.LEDGER.statuses();
    private static final String BANK_A = "004";
    private static final String BANK_B = "088";

    private final ChargeRecoverySteps steps = mock(ChargeRecoverySteps.class);
    private final ChargeSagaExecutor executor = mock(ChargeSagaExecutor.class);
    private final BankAvailability bankAvailability = mock(BankAvailability.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ChargeRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(steps.findTargets(any(), any(), anyInt())).thenReturn(List.of());
        when(steps.claim(any())).thenReturn(true);
        when(bankAvailability.openBankCodes()).thenReturn(Set.of());
        scheduler = new ChargeRecoveryScheduler(steps, executor, bankAvailability, meterRegistry, BATCH, "10s",
                nanos::get);
    }

    @Test
    void 가득_찬_배치면_같은_주기에서_이어서_조회하고_덜_찬_배치에서_멈춘다() {
        when(steps.findTargets(eq(BANK), any(), eq(BATCH))).thenReturn(
                targets(ChargeStatus.BANK_PROCESSING, 10), targets(ChargeStatus.BANK_PROCESSING, 10),
                targets(ChargeStatus.BANK_PROCESSING, 3));
        when(executor.execute(any(), anyString())).thenReturn(answered(ChargeStatus.BANK_PROCESSING));

        scheduler.recover();

        verify(steps, times(3)).findTargets(eq(BANK), any(), eq(BATCH));
        verify(steps, times(1)).findTargets(eq(LEDGER), any(), eq(BATCH));
        verify(executor, times(23)).execute(any(), anyString());
    }

    @Test
    void 시간_한도가_지나면_새_배치를_시작하지_않지만_첫_바퀴는_모든_묶음을_처리한다() {
        when(steps.findTargets(eq(BANK), any(), eq(BATCH))).thenReturn(targets(ChargeStatus.BANK_PROCESSING, 10));
        when(steps.findTargets(eq(LEDGER), any(), eq(BATCH))).thenReturn(targets(ChargeStatus.CREDIT_PROCESSING, 10));
        // 실행 한 건마다 1초가 지난다 → 은행 배치 하나로 8초 한도를 넘김
        when(executor.execute(any(), anyString())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
            return answered(ChargeStatus.BANK_PROCESSING);
        });

        scheduler.recover();

        verify(steps, times(1)).findTargets(eq(BANK), any(), eq(BATCH));
        verify(steps, times(1)).findTargets(eq(LEDGER), any(), eq(BATCH));
        verify(executor, times(20)).execute(any(), anyString());
    }

    @Test
    void 한_은행에서_결과_모름이_3번_연달아_나오면_그_은행만_멈추고_원장_묶음은_계속한다() {
        List<ChargeRecoveryTarget> bank = targets(ChargeStatus.BANK_PROCESSING, 10);
        stubBankTargets(bank);
        when(steps.findTargets(eq(LEDGER), any(), eq(BATCH))).thenReturn(
                targets(ChargeStatus.CREDIT_PROCESSING, 10), targets(ChargeStatus.CREDIT_PROCESSING, 2));
        when(executor.execute(any(), anyString())).thenAnswer(invocation ->
                isIn(invocation.getArgument(0), bank)
                        ? unknown(ChargeStatus.BANK_PROCESSING)
                        : answered(ChargeStatus.CREDIT_PROCESSING));

        scheduler.recover();

        // 3건 뒤 같은 은행 건은 선점하지 않고, 가득 찬 배치라 다시 조회할 때 그 은행을 뺀다
        for (int i = 0; i < 10; i++) {
            verify(executor, times(i < 3 ? 1 : 0)).execute(eq(bank.get(i).id()), anyString());
            verify(steps, times(i < 3 ? 1 : 0)).claim(bank.get(i));
        }
        verify(steps).findTargets(eq(BANK), eq(Set.of()), eq(BATCH));
        verify(steps).findTargets(eq(BANK), eq(Set.of(BANK_A)), eq(BATCH));
        verify(steps, times(2)).findTargets(eq(LEDGER), eq(Set.of()), eq(BATCH));
        verify(executor, times(15)).execute(any(), anyString());
    }

    @Test
    void 결과_모름_3연속은_은행별로_세어_다른_은행_복구가_밀리지_않는다() {
        // 004·088이 번갈아 나오고 004만 결과 모름
        List<ChargeRecoveryTarget> bank = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bank.add(target(ChargeStatus.BANK_PROCESSING, i % 2 == 0 ? BANK_A : BANK_B));
        }
        stubBankTargets(bank);
        when(executor.execute(any(), anyString())).thenAnswer(invocation -> {
            ChargeRecoveryTarget target = find(invocation.getArgument(0), bank);
            return BANK_A.equals(target.bankCode())
                    ? unknown(ChargeStatus.BANK_PROCESSING, BANK_A)
                    : answered(ChargeStatus.BANK_PROCESSING, BANK_B);
        });

        scheduler.recover();

        long bankAExecuted = bank.stream().filter(t -> BANK_A.equals(t.bankCode()))
                .filter(t -> executed(t.id())).count();
        long bankBExecuted = bank.stream().filter(t -> BANK_B.equals(t.bankCode()))
                .filter(t -> executed(t.id())).count();
        assertEquals(3, bankAExecuted);
        assertEquals(5, bankBExecuted);
        // 다시 조회할 때 004만 빠지고 088은 그대로 조회 대상
        verify(steps).findTargets(eq(BANK), eq(Set.of(BANK_A)), eq(BATCH));
    }

    @Test
    void 은행_묶음_대상이_원장_단계에서_결과_모름이면_원장_묶음으로_센다() {
        when(steps.findTargets(eq(BANK), any(), eq(BATCH))).thenReturn(
                targets(ChargeStatus.BANK_PROCESSING, 10), targets(ChargeStatus.BANK_PROCESSING, 1));
        when(steps.findTargets(eq(LEDGER), any(), eq(BATCH))).thenReturn(targets(ChargeStatus.CREDIT_PROCESSING, 10));
        // 출금은 확실한 응답, 적립은 결과 모름
        when(executor.execute(any(), anyString())).thenReturn(
                new ChargeSagaOutcome(Set.of(ChargeStatus.BANK_PROCESSING), ChargeStatus.CREDIT_PROCESSING, BANK_A,
                        null));

        scheduler.recover();

        // 은행 묶음은 멈추지 않고, 원장 묶음은 장애로 보아 조회하지 않는다
        verify(steps, times(2)).findTargets(eq(BANK), any(), eq(BATCH));
        verify(steps, never()).findTargets(eq(LEDGER), any(), anyInt());
        verify(executor, times(11)).execute(any(), anyString());
    }

    @Test
    void 확실한_응답이_끼면_연속_횟수가_처음부터_다시_센다() {
        when(steps.findTargets(eq(BANK), any(), eq(BATCH))).thenReturn(targets(ChargeStatus.BANK_PROCESSING, 6));
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
        when(steps.findTargets(eq(BANK), any(), eq(BATCH))).thenReturn(bank);
        when(steps.claim(any())).thenReturn(true, false, true, false, true, true);
        when(executor.execute(any(), anyString())).thenReturn(unknown(ChargeStatus.BANK_PROCESSING));

        scheduler.recover();

        verify(steps, times(5)).claim(any());
        verify(steps, never()).claim(bank.get(5));
        verify(executor, times(3)).execute(any(), anyString());
    }

    @Test
    void 실행기_예외는_대상_은행의_결과_모름으로_세고_다른_은행은_계속한다() {
        List<ChargeRecoveryTarget> bank = new ArrayList<>(targets(ChargeStatus.REQUESTED, 5));
        List<ChargeRecoveryTarget> other = List.of(target(ChargeStatus.REQUESTED, BANK_B),
                target(ChargeStatus.REQUESTED, BANK_B));
        bank.addAll(other);
        stubBankTargets(bank);
        when(executor.execute(any(), anyString())).thenAnswer(invocation -> {
            if (isIn(invocation.getArgument(0), other)) {
                return answered(ChargeStatus.BANK_PROCESSING, BANK_B);
            }
            throw new IllegalStateException("boom");
        });

        scheduler.recover();

        verify(executor, times(5)).execute(any(), anyString());
        for (ChargeRecoveryTarget target : other) {
            verify(executor).execute(eq(target.id()), anyString());
        }
    }

    @Test
    void NotSent가_나면_결과_모름_연속과_별개로_그_은행은_이번_주기에서_빠진다() {
        // 004 첫 건이 NotSent(결과 모름 1회도 아님) → 이후 004는 선점하지 않음, 088은 계속
        List<ChargeRecoveryTarget> bank = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bank.add(target(ChargeStatus.BANK_PROCESSING, i < 5 ? BANK_A : BANK_B));
        }
        stubBankTargets(bank);
        when(executor.execute(any(), anyString())).thenAnswer(invocation ->
                BANK_A.equals(find(invocation.getArgument(0), bank).bankCode())
                        ? notSent(BANK_A)
                        : answered(ChargeStatus.BANK_PROCESSING, BANK_B));

        scheduler.recover();

        verify(steps).claim(bank.get(0));
        for (int i = 1; i < 5; i++) {
            verify(steps, never()).claim(bank.get(i));
        }
        // 가득 찬 배치라 004를 빼고 다시 조회하므로 088은 한 번 더 나올 수 있다
        for (int i = 5; i < 10; i++) {
            verify(executor, atLeastOnce()).execute(eq(bank.get(i).id()), anyString());
        }
        verify(steps).findTargets(eq(BANK), eq(Set.of(BANK_A)), eq(BATCH));
    }

    @Test
    void 서킷이_열린_은행은_은행_묶음_조회에서_빼고_원장_묶음은_거르지_않는다() {
        when(bankAvailability.openBankCodes()).thenReturn(Set.of(BANK_A));

        scheduler.recover();

        verify(steps).findTargets(eq(BANK), eq(Set.of(BANK_A)), eq(BATCH));
        verify(steps).findTargets(eq(LEDGER), eq(Set.of()), eq(BATCH));
    }

    @Test
    void 열린_은행이_없으면_빈_제외_목록으로_조회한다() {
        scheduler.recover();

        verify(steps).findTargets(eq(BANK), eq(Set.of()), eq(BATCH));
    }

    @Test
    void 한_묶음의_조회가_실패해도_다른_묶음과_지표는_처리한다() {
        when(steps.findTargets(eq(BANK), any(), eq(BATCH))).thenThrow(new IllegalStateException("db"));
        when(steps.findTargets(eq(LEDGER), any(), eq(BATCH))).thenReturn(targets(ChargeStatus.CREDIT_PROCESSING, 2));
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

    /** 은행 묶음 조회가 SQL처럼 제외 목록의 은행을 뺀 대상을 돌려준다 */
    private void stubBankTargets(List<ChargeRecoveryTarget> bank) {
        when(steps.findTargets(eq(BANK), any(), eq(BATCH))).thenAnswer(invocation -> {
            Collection<String> excluded = invocation.getArgument(1);
            return bank.stream().filter(target -> !excluded.contains(target.bankCode())).toList();
        });
    }

    private boolean executed(UUID chargeId) {
        return mockingDetails(executor).getInvocations().stream()
                .anyMatch(invocation -> chargeId.equals(invocation.getArguments()[0]));
    }

    private double stuckGauge() {
        return meterRegistry.get(ChargeRecoveryScheduler.STUCK_METRIC).gauge().value();
    }

    private static List<ChargeRecoveryTarget> targets(ChargeStatus status, int count) {
        List<ChargeRecoveryTarget> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            targets.add(target(status, BANK_A));
        }
        return targets;
    }

    private static ChargeRecoveryTarget target(ChargeStatus status, String bankCode) {
        return new ChargeRecoveryTarget(UUID.randomUUID(), status, Instant.now(), bankCode);
    }

    private static boolean isIn(UUID chargeId, List<ChargeRecoveryTarget> targets) {
        return targets.stream().anyMatch(target -> target.id().equals(chargeId));
    }

    private static ChargeRecoveryTarget find(UUID chargeId, List<ChargeRecoveryTarget> targets) {
        return targets.stream().filter(target -> target.id().equals(chargeId)).findFirst().orElseThrow();
    }

    private static ChargeSagaOutcome answered(ChargeStatus status) {
        return answered(status, BANK_A);
    }

    private static ChargeSagaOutcome answered(ChargeStatus status, String bankCode) {
        return new ChargeSagaOutcome(Set.of(status), null, bankCode, null);
    }

    private static ChargeSagaOutcome unknown(ChargeStatus status) {
        return unknown(status, BANK_A);
    }

    private static ChargeSagaOutcome unknown(ChargeStatus status, String bankCode) {
        return new ChargeSagaOutcome(Set.of(), status, bankCode, null);
    }

    private static ChargeSagaOutcome notSent(String bankCode) {
        return new ChargeSagaOutcome(Set.of(), null, bankCode, bankCode);
    }
}

package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 멈춘 충전 복구. 처리 중 상태로 재시도 대기 시간이 지난 건을 선점해 SAGA 실행기를 다시 돌린다.
 * 선점은 건마다 짧은 트랜잭션으로 커밋하고, 실행기는 트랜잭션 밖에서 호출한다 (외부 호출 동안 행 잠금을 잡지 않도록).
 * 이 메서드와 배치 루프에는 @Transactional을 붙이지 않는다.
 * <p>
 * 단계 묶음(은행·원장, {@link ChargeRecoveryGroup})마다 따로 조회해 배치 하나씩 번갈아 처리한다.
 * 배치가 가득 차면 한 주기 안에서 이어서 조회하고, 결과 모름이 연달아 나온 묶음은 그 외부 의존성 장애로 보고
 * 이번 주기에 더 처리하지 않는다 (다음 주기에 다시 시도). 백오프·MANUAL_REVIEW 한도·SAGA 전이는 실행기·조회 조건 그대로다.
 * 주기마다 멈춘 충전 수(charge.recovery.stuck)도 갱신한다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "travelpay.wallet.charge.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ChargeRecoveryScheduler {

    /**
     * 추가 배치를 시작하지 않는 마감이며 실행 시간 상한이 아니다. 이미 시작한 배치는 끊지 않는다.
     * 은행이 응답 없이 붙잡으면 호출당 약 6초(연결 1초 + 읽기 5초)라 결과 모름 3연속으로 멈추기까지만 18초 이상 걸릴 수 있고,
     * 실제 소요 시간은 배치 안 건들의 처리 경로(출금 후 원장·환불까지 이어지는지 등)에 따라 달라진다
     */
    static final Duration ROUND_BUDGET = Duration.ofSeconds(8);
    /** 한 묶음에서 결과 모름이 이 횟수만큼 연달아 나오면 그 묶음은 이번 주기에 중단 */
    static final int MAX_CONSECUTIVE_UNKNOWN = 3;
    /** 생성 후 이 시간이 지나도 처리 중이면 멈춘 충전으로 센다 */
    static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    static final String STUCK_METRIC = "charge.recovery.stuck";

    private static final String BATCH_SIZE_KEY = "travelpay.wallet.charge.recovery.batch-size";
    private static final String FIXED_DELAY_KEY = "travelpay.wallet.charge.recovery.fixed-delay";
    private static final Duration MIN_FIXED_DELAY = Duration.ofSeconds(1);

    private final ChargeRecoverySteps recoverySteps;
    private final ChargeSagaExecutor executor;
    private final int batchSize;
    private final LongSupplier nanoClock;
    private final AtomicLong stuckCharges = new AtomicLong();

    /** 설정이 잘못되면 IllegalStateException으로 기동을 멈춘다 (@Scheduled 등록 전) */
    @Autowired
    public ChargeRecoveryScheduler(ChargeRecoverySteps recoverySteps, ChargeSagaExecutor executor,
                                   MeterRegistry meterRegistry,
                                   @Value("${" + BATCH_SIZE_KEY + ":10}") int batchSize,
                                   @Value("${" + FIXED_DELAY_KEY + ":10s}") String fixedDelay) {
        this(recoverySteps, executor, meterRegistry, batchSize, fixedDelay, System::nanoTime);
    }

    /** 테스트에서 시간 한도를 확인하려고 시각 공급자를 받는다 */
    ChargeRecoveryScheduler(ChargeRecoverySteps recoverySteps, ChargeSagaExecutor executor,
                            MeterRegistry meterRegistry, int batchSize, String fixedDelay, LongSupplier nanoClock) {
        if (batchSize < 1) {
            throw new IllegalStateException(BATCH_SIZE_KEY + "는 1 이상이어야 합니다: " + batchSize);
        }
        requireFixedDelay(fixedDelay);
        this.recoverySteps = recoverySteps;
        this.executor = executor;
        this.batchSize = batchSize;
        this.nanoClock = nanoClock;
        Gauge.builder(STUCK_METRIC, stuckCharges, AtomicLong::get)
                .description("처리 중 상태이면서 생성 후 30분이 지난 충전 수")
                .register(meterRegistry);
    }

    /**
     * ScheduledAnnotationBeanPostProcessor와 같은 파서로 해석해 1초 이상인지 확인.
     * "10s"·"1m30s"·ISO-8601을 받고, 단위 없는 숫자는 @Scheduled 기본 timeUnit처럼 밀리초로 본다
     */
    private static void requireFixedDelay(String fixedDelay) {
        Duration delay;
        try {
            delay = DurationFormatterUtils.detectAndParse(fixedDelay, DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(FIXED_DELAY_KEY + " 형식이 잘못되었습니다: " + fixedDelay, e);
        }
        if (delay.compareTo(MIN_FIXED_DELAY) < 0) {
            throw new IllegalStateException(FIXED_DELAY_KEY + "는 1초 이상이어야 합니다: " + fixedDelay);
        }
    }

    @Scheduled(fixedDelayString = "${travelpay.wallet.charge.recovery.fixed-delay:10s}")
    public void recover() {
        drain();
        refreshStuck();
    }

    /** 조회에 실패하면 로그만 남기고 이전 값을 유지한다 */
    private void refreshStuck() {
        try {
            stuckCharges.set(recoverySteps.countStuck(Instant.now().minus(STUCK_AFTER)));
        } catch (RuntimeException e) {
            log.error("멈춘 충전 수 조회 실패 exception={}", e.getClass().getName());
        }
    }

    /**
     * 묶음마다 배치 하나씩 돌아가며 처리. 덜 찬 배치·결과 모름 연속·조회 실패가 나온 묶음은 빠지고,
     * 모두 빠지거나 ROUND_BUDGET이 지나면 끝. 첫 바퀴는 시간과 관계없이 모든 묶음이 한 번씩 돈다
     */
    private void drain() {
        long deadline = nanoClock.getAsLong() + ROUND_BUDGET.toNanos();
        Round round = new Round();
        List<ChargeRecoveryGroup> active = new ArrayList<>(List.of(ChargeRecoveryGroup.values()));
        while (!active.isEmpty()) {
            Iterator<ChargeRecoveryGroup> it = active.iterator();
            while (it.hasNext()) {
                if (!runBatch(it.next(), round)) {
                    it.remove();
                }
            }
            if (nanoClock.getAsLong() >= deadline) {
                break;
            }
        }
    }

    /** 배치 하나를 처리하고, 이 묶음을 이어서 더 처리할지 돌려준다 */
    private boolean runBatch(ChargeRecoveryGroup group, Round round) {
        if (round.unavailable(group)) {
            return false;
        }
        long startedAt = System.nanoTime();
        List<ChargeRecoveryTarget> targets;
        try {
            targets = recoverySteps.findTargets(group.statuses(), batchSize);
        } catch (RuntimeException e) {
            log.error("충전 복구 대상 조회 실패 group={} exception={}", group, e.getClass().getName());
            return false;
        }
        if (targets.isEmpty()) {
            return false;
        }
        int claimed = 0;
        int skipped = 0;
        int failed = 0;
        boolean stopped = false;
        for (ChargeRecoveryTarget target : targets) {
            if (round.unavailable(group)) {
                stopped = true;
                break;
            }
            try {
                if (!recoverySteps.claim(target)) {
                    skipped++;
                    continue;
                }
                claimed++;
                round.record(executor.execute(target.id(), "recovery-" + UUID.randomUUID()));
            } catch (RuntimeException e) {
                // 허용되지 않은 전이 등 예상 밖 예외로 같은 배치의 다른 건이 멈추지 않게 한다.
                // 어느 단계에서 났는지 모르므로 대상 묶음의 결과 모름으로 센다
                failed++;
                round.recordUnknown(group);
                log.error("충전 복구 실행 실패 chargeId={} status={} exception={}",
                        target.id(), target.status(), e.getClass().getName());
            }
        }
        stopped = stopped || round.unavailable(group);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("충전 복구 배치 group={} found={} claimed={} claimMissed={} failed={} unknownStop={} elapsedMs={}",
                group, targets.size(), claimed, skipped, failed, stopped, elapsedMs);
        if (stopped) {
            log.warn("{} 결과 모름이 {}번 연달아 나와 {} 단계 복구를 다음 주기로 미룹니다",
                    group.label(), MAX_CONSECUTIVE_UNKNOWN, group);
        }
        return !stopped && targets.size() >= batchSize;
    }

    /**
     * 한 주기 동안 묶음별 결과 모름 연속 횟수. 대상의 묶음이 아니라 외부 호출이 끝난 단계의 묶음으로 센다
     * (은행 묶음 대상이 출금 성공 후 원장에서 결과 모름이면 은행 0, 원장 +1).
     * 외부 의존성의 상태를 보는 것이므로 결과 모름 기록이 DB에 반영됐는지(RECORDED·EXHAUSTED·NOT_RECORDED)와 무관하다
     */
    private static final class Round {
        private final Map<ChargeRecoveryGroup, Integer> consecutiveUnknown = new EnumMap<>(ChargeRecoveryGroup.class);

        private void record(ChargeSagaOutcome outcome) {
            if (outcome == null) {
                return;
            }
            // 결과 모름은 항상 마지막 외부 호출이라 확실한 응답을 먼저 반영한다
            for (ChargeStatus answered : outcome.answeredAt()) {
                consecutiveUnknown.put(ChargeRecoveryGroup.of(answered), 0);
            }
            outcome.unknownStatus().ifPresent(status -> recordUnknown(ChargeRecoveryGroup.of(status)));
        }

        private void recordUnknown(ChargeRecoveryGroup group) {
            consecutiveUnknown.merge(group, 1, Integer::sum);
        }

        private boolean unavailable(ChargeRecoveryGroup group) {
            return consecutiveUnknown.getOrDefault(group, 0) >= MAX_CONSECUTIVE_UNKNOWN;
        }
    }
}

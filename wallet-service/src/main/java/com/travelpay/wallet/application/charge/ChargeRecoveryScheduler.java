package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;

/**
 * 멈춘 충전 복구. 처리 중 상태로 재시도 대기 시간이 지난 건을 선점해 SAGA 실행기를 다시 돌린다.
 * 선점은 건마다 짧은 트랜잭션으로 커밋하고, 실행기는 트랜잭션 밖에서 호출한다 (외부 호출 동안 행 잠금을 잡지 않도록).
 * 이 메서드와 배치 루프에는 @Transactional을 붙이지 않는다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "travelpay.wallet.charge.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ChargeRecoveryScheduler {

    private final ChargeRecoverySteps recoverySteps;
    private final ChargeSagaExecutor executor;
    private final int batchSize;

    public ChargeRecoveryScheduler(ChargeRecoverySteps recoverySteps, ChargeSagaExecutor executor,
                                   @Value("${travelpay.wallet.charge.recovery.batch-size:10}") int batchSize) {
        this.recoverySteps = recoverySteps;
        this.executor = executor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${travelpay.wallet.charge.recovery.fixed-delay:10s}")
    public void recover() {
        long startedAt = System.nanoTime();
        List<ChargeRecoveryTarget> targets;
        try {
            targets = recoverySteps.findTargets(batchSize);
        } catch (RuntimeException e) {
            log.error("충전 복구 대상 조회 실패 exception={}", e.getClass().getName());
            return;
        }
        if (targets.isEmpty()) {
            return;
        }
        int claimed = 0;
        int skipped = 0;
        int failed = 0;
        for (ChargeRecoveryTarget target : targets) {
            try {
                if (!recoverySteps.claim(target)) {
                    skipped++;
                    continue;
                }
                claimed++;
                executor.execute(target.id(), "recovery-" + UUID.randomUUID());
            } catch (RuntimeException e) {
                // 허용되지 않은 전이 등 예상 밖 예외로 같은 배치의 다른 건이 멈추지 않게 한다
                failed++;
                log.error("충전 복구 실행 실패 chargeId={} status={} exception={}",
                        target.id(), target.status(), e.getClass().getName());
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("충전 복구 배치 found={} claimed={} claimMissed={} failed={} elapsedMs={}",
                targets.size(), claimed, skipped, failed, elapsedMs);
    }
}

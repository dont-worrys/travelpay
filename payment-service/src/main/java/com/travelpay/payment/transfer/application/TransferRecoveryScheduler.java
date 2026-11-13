package com.travelpay.payment.transfer.application;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 원장 거래 등록 결과를 모른 채 처리 중(PROCESSING)으로 남은 송금을 같은 reference로 원장에 다시 요청합니다.
 * 재시도 간격(30초 → 1분 → 2분 → 4분)과 한도(결과 모름 5회면 MANUAL_REVIEW)는 지갑 충전 복구와 같습니다.
 * 원장 장애로 밀린 송금이 회복 시점에 한꺼번에 몰리지 않도록 건마다 대기 시간을 늘리고, 한 주기에 다루는 건수를 제한합니다.
 * 서버가 여러 대면 모두 돌지만, 복구 대상은 SKIP LOCKED로 선점해 서버끼리 서로 다른 송금을 나눠 처리합니다
 * ({@link TransferService#recoverDue}). 분산 락은 쓰지 않습니다.
 * 6주기(약 1분)마다 MANUAL_REVIEW 송금을 원장 결과로 자동 대사합니다({@link TransferReviewReconciler}).
 * 결제 정산 복구와 스레드를 나눈 풀(2개)을 늘리지 않도록 같은 스레드에서 복구 뒤에 실행합니다.
 * transfer.recovery.enabled=false이면 돌지 않습니다. (테스트·수동 확인용)
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "transfer.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransferRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TransferRecoveryScheduler.class);

    static final int BATCH_SIZE = 10;

    // 재시도가 모두 끝나는 시간(약 8분)보다 넉넉히 지나도 처리 중이면 복구가 돌지 않는 것으로 보고 알립니다.
    static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    // 로그 한 줄이 너무 길어지지 않도록 오래된 순 10개만 남깁니다.
    static final int STUCK_SAMPLE_SIZE = 10;
    // MANUAL_REVIEW 자동 대사는 6주기(약 1분)마다, 한 번에 10건까지 봅니다.
    static final int RECONCILE_EVERY_CYCLES = 6;
    static final int RECONCILE_BATCH_SIZE = 10;

    private final TransferService transferService;
    private final TransferReviewReconciler reviewReconciler;
    private long cycle;
    private final AtomicLong stuckTransfers = new AtomicLong();
    private final AtomicLong manualReviewTransfers = new AtomicLong();

    public TransferRecoveryScheduler(TransferService transferService, TransferReviewReconciler reviewReconciler,
            MeterRegistry meterRegistry) {
        this.transferService = transferService;
        this.reviewReconciler = reviewReconciler;
        Gauge.builder("transfer.processing.stuck", stuckTransfers, AtomicLong::get)
                .description("생성 후 30분이 지나도 확정되지 않은 처리 중 송금 수")
                .register(meterRegistry);
        Gauge.builder("transfer.manual_review", manualReviewTransfers, AtomicLong::get)
                .description("자동 처리를 멈추고 운영자 확인을 기다리는 송금 수")
                .register(meterRegistry);
    }

    /** 한 주기가 실패해도 다음 주기는 돌도록 예외를 로그로만 남깁니다. */
    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void recover() {
        Instant now = Instant.now();
        try {
            transferService.recoverDue(now, BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.error("처리 중 송금 복구 주기가 실패했습니다.", exception);
        }
        if (cycle++ % RECONCILE_EVERY_CYCLES == 0) {
            try {
                reviewReconciler.reconcile(now, RECONCILE_BATCH_SIZE);
            } catch (RuntimeException exception) {
                log.error("MANUAL_REVIEW 자동 대사 주기가 실패했습니다.", exception);
            }
        }
        try {
            reportStuck(now.minus(STUCK_AFTER));
            manualReviewTransfers.set(transferService.countManualReview());
        } catch (RuntimeException exception) {
            log.error("확정되지 않은 송금 수를 조회하지 못했습니다.", exception);
        }
    }

    private void reportStuck(Instant createdBefore) {
        StuckTransfers stuck = transferService.findStuckProcessing(createdBefore, STUCK_SAMPLE_SIZE);
        stuckTransfers.set(stuck.count());
        if (stuck.count() > 0) {
            log.error("30분 넘게 확정되지 않은 처리 중 송금 {}건, 오래된 순 transferIds={}", stuck.count(), stuck.sampleIds());
        }
    }
}

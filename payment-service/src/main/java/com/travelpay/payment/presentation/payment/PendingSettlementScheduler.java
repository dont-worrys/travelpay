package com.travelpay.payment.presentation.payment;

import com.travelpay.payment.application.payment.ManualReviewLock;
import com.travelpay.payment.application.payment.ManualReviewReconciler;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.RecoveryBatchResult;
import com.travelpay.payment.application.payment.dto.StuckSettlementResult;
import com.travelpay.payment.application.merchant.SettlementService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 결과를 모른 채 남은 결제·환불·정산을 주기적으로 같은 referenceId로 원장에 다시 요청해 확정
 * 원장 장애로 밀린 건이 회복 시점에 한꺼번에 몰리지 않도록 건마다 대기 시간을 늘린다.
 * 재시도할 건이 많이 쌓이면 한 주기 안에서 결제·환불·정산을 배치 단위로 돌아가며 이어서 처리하고(ROUND_BUDGET까지),
 * 결과 모름이 연달아 나온 종류는 원장 장애로 보고 그 주기에서 더 처리하지 않는다.
 * 6주기(약 1분)마다 복구 뒤에 MANUAL_REVIEW를 원장 결과로 자동 처리한다({@link ManualReviewReconciler}).
 * 서버가 여러 대면 복구는 모두 돌며 대상을 SKIP LOCKED로 일괄 선점해 서로 다른 건을 처리하고,
 * 자동 처리는 잠금({@link ManualReviewLock})을 잡은 서버 한 곳만 그 차례를 처리한다.
 * 스케줄러 풀(2개)을 송금 복구와 나눠 쓰므로 새 스케줄 작업을 만들지 않고 같은 스레드에서 실행한다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "payment.settlement-recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingSettlementScheduler {

    private static final int BATCH_SIZE = 10;
    // 한 주기에서 이어서 처리하는 시간 한도. 주기 간격(10초)보다 짧게 두어 지표 갱신이 매 주기 이어지게 함
    static final Duration ROUND_BUDGET = Duration.ofSeconds(8);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    // 로그에 남길 ID 개수. 로그 한 줄이 너무 길어지지 않게 오래된 순 10개만.
    private static final int STUCK_SAMPLE_SIZE = 10;
    // MANUAL_REVIEW 자동 처리는 6주기(약 1분)마다
    static final int RECONCILE_EVERY_CYCLES = 6;

    private final PaymentService paymentService;
    private final SettlementService settlementService;
    private final ManualReviewReconciler manualReviewReconciler;
    private final ManualReviewLock manualReviewLock;
    private final LongSupplier nanoClock;
    private long cycle;

    private final AtomicLong stuckPayments = new AtomicLong();
    private final AtomicLong stuckRefunds = new AtomicLong();
    private final AtomicLong stuckSettlements = new AtomicLong();
    private final AtomicLong manualReviewPayments = new AtomicLong();
    private final AtomicLong manualReviewRefunds = new AtomicLong();
    private final AtomicLong manualReviewSettlements = new AtomicLong();
    private final AtomicLong overdueRefundRequests = new AtomicLong();

    // 생성자가 둘이라 스프링이 쓸 생성자를 지정
    @Autowired
    public PendingSettlementScheduler(PaymentService paymentService, SettlementService settlementService,
                                      ManualReviewReconciler manualReviewReconciler, ManualReviewLock manualReviewLock,
                                      MeterRegistry meterRegistry) {
        this(paymentService, settlementService, manualReviewReconciler, manualReviewLock, meterRegistry,
                System::nanoTime);
    }

    // 테스트에서 시간 한도를 확인하려고 시각 공급자를 받음
    PendingSettlementScheduler(PaymentService paymentService, SettlementService settlementService,
                               ManualReviewReconciler manualReviewReconciler, ManualReviewLock manualReviewLock,
                               MeterRegistry meterRegistry, LongSupplier nanoClock) {
        this.paymentService = paymentService;
        this.settlementService = settlementService;
        this.manualReviewReconciler = manualReviewReconciler;
        this.manualReviewLock = manualReviewLock;
        this.nanoClock = nanoClock;
        Gauge.builder("payment.settlement.stuck", stuckPayments, AtomicLong::get)
                .description("요청 후 30분이 지나도 확정되지 않은 건수")
                .tag("type", "payment")
                .register(meterRegistry);
        Gauge.builder("payment.settlement.stuck", stuckRefunds, AtomicLong::get)
                .description("요청 후 30분이 지나도 확정되지 않은 건수")
                .tag("type", "refund")
                .register(meterRegistry);
        Gauge.builder("payment.settlement.stuck", stuckSettlements, AtomicLong::get)
                .description("요청 후 30분이 지나도 확정되지 않은 건수")
                .tag("type", "settlement")
                .register(meterRegistry);
        Gauge.builder("payment.settlement.manual_review", manualReviewPayments, AtomicLong::get)
                .description("자동 재시도를 멈추고 운영자 확인을 기다리는 건수")
                .tag("type", "payment")
                .register(meterRegistry);
        Gauge.builder("payment.settlement.manual_review", manualReviewRefunds, AtomicLong::get)
                .description("자동 재시도를 멈추고 운영자 확인을 기다리는 건수")
                .tag("type", "refund")
                .register(meterRegistry);
        Gauge.builder("payment.settlement.manual_review", manualReviewSettlements, AtomicLong::get)
                .description("자동 재시도를 멈추고 운영자 확인을 기다리는 건수")
                .tag("type", "settlement")
                .register(meterRegistry);
        Gauge.builder("payment.refund_request.overdue", overdueRefundRequests, AtomicLong::get)
                .description("고객이 신청한 뒤 3일 넘게 가맹점이 처리하지 않은 환불 신청 건수")
                .register(meterRegistry);
    }

    /** 한 주기가 실패해도 다음 주기는 돌도록 예외를 로그로만 남긴다. 결제 복구가 실패해도 환불 복구는 돈다 */
    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void recover() {
        Instant now = Instant.now();
        drain();
        // 복구를 먼저 끝내고, 지표 갱신 전에 실행해 처리 결과가 같은 주기의 MANUAL_REVIEW 수에 반영되게 함
        if (cycle++ % RECONCILE_EVERY_CYCLES == 0) {
            try {
                // 다른 서버가 이번 차례를 처리 중이면 건너뜀
                if (!manualReviewLock.runExclusively(() -> manualReviewReconciler.reconcile(Instant.now()))) {
                    log.debug("다른 서버가 MANUAL_REVIEW 자동 처리 중이라 이번 차례는 건너뜁니다.");
                }
            } catch (RuntimeException e) {
                log.error("MANUAL_REVIEW 자동 처리 주기가 실패했습니다.", e);
            }
        }
        try {
            reportStuck(now.minus(STUCK_AFTER));
            manualReviewPayments.set(paymentService.countManualReviewPayments());
            manualReviewRefunds.set(paymentService.countManualReviewRefunds());
            manualReviewSettlements.set(settlementService.countManualReviewSettlements());
            overdueRefundRequests.set(paymentService.countOverdueRefundRequests());
        } catch (RuntimeException e) {
            log.error("확정되지 않은 결제·환불·정산 수를 조회하지 못했습니다.", e);
        }
    }

    // 결제·환불·정산을 배치 하나씩 돌아가며 처리. 덜 찬 배치·원장 장애·오류가 나온 종류는 빠지고,
    // 모두 빠지거나 시간 한도가 지나면 끝. 첫 바퀴는 시간과 관계없이 세 종류 모두 한 번씩 돈다
    private void drain() {
        long deadline = nanoClock.getAsLong() + ROUND_BUDGET.toNanos();
        List<Recovery> active = new ArrayList<>(List.of(
                new Recovery("결제", () -> paymentService.recoverPayments(Instant.now(), BATCH_SIZE)),
                new Recovery("환불", () -> paymentService.recoverRefunds(Instant.now(), BATCH_SIZE)),
                new Recovery("정산", () -> settlementService.recoverSettlements(Instant.now(), BATCH_SIZE))));
        List<Recovery> all = List.copyOf(active);
        while (!active.isEmpty()) {
            Iterator<Recovery> it = active.iterator();
            while (it.hasNext()) {
                if (!it.next().runBatch()) {
                    it.remove();
                }
            }
            if (nanoClock.getAsLong() >= deadline) {
                break;
            }
        }
        int total = all.stream().mapToInt(r -> r.fetched).sum();
        if (total > BATCH_SIZE * all.size()) {
            log.info("복구 주기 처리 결제 {}건, 환불 {}건, 정산 {}건", all.get(0).fetched, all.get(1).fetched, all.get(2).fetched);
        }
    }

    private static final class Recovery {
        private final String name;
        private final Supplier<RecoveryBatchResult> batch;
        private int fetched;

        private Recovery(String name, Supplier<RecoveryBatchResult> batch) {
            this.name = name;
            this.batch = batch;
        }

        // 배치 하나를 처리하고, 이어서 더 처리할지 돌려줌
        private boolean runBatch() {
            RecoveryBatchResult result;
            try {
                result = batch.get();
            } catch (RuntimeException e) {
                log.error("{} 복구 주기가 실패했습니다.", name, e);
                return false;
            }
            if (result == null) {
                return false;
            }
            fetched += result.fetched();
            return result.fetched() >= BATCH_SIZE && !result.ledgerUnavailable();
        }
    }

    private void reportStuck(Instant createdBefore) {
        StuckSettlementResult stuck = paymentService.findStuckSettlements(createdBefore, STUCK_SAMPLE_SIZE);
        stuckPayments.set(stuck.paymentCount());
        stuckRefunds.set(stuck.refundCount());
        if (stuck.paymentCount() > 0) {
            log.error("30분 넘게 확정되지 않은 결제 {}건, 오래된 순 paymentIds={}",
                    stuck.paymentCount(), stuck.paymentIds());
        }
        if (stuck.refundCount() > 0) {
            log.error("30분 넘게 확정되지 않은 환불 {}건, 오래된 순 refundIds={}",
                    stuck.refundCount(), stuck.refundIds());
        }
        long settlementCount = settlementService.countStuckSettlements(createdBefore);
        stuckSettlements.set(settlementCount);
        if (settlementCount > 0) {
            log.error("30분 넘게 확정되지 않은 정산 {}건, 오래된 순 settlementIds={}",
                    settlementCount, settlementService.findStuckSettlementIds(createdBefore, STUCK_SAMPLE_SIZE));
        }
    }
}

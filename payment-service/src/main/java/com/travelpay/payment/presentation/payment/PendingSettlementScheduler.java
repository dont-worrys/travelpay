package com.travelpay.payment.presentation.payment;

import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.StuckSettlementResult;
import com.travelpay.payment.application.merchant.SettlementService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 결과를 모른 채 남은 결제·환불·정산을 주기적으로 같은 referenceId로 원장에 다시 요청해 확정
 * 원장 장애로 밀린 건이 회복 시점에 한꺼번에 몰리지 않도록 건마다 대기 시간을 늘리고, 한 주기에 다루는 건수를 제한
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "payment.settlement-recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingSettlementScheduler {

    private static final int BATCH_SIZE = 10;
    private static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    // 로그에 남길 ID 개수. 로그 한 줄이 너무 길어지지 않게 오래된 순 10개만.
    private static final int STUCK_SAMPLE_SIZE = 10;

    private final PaymentService paymentService;
    private final SettlementService settlementService;

    private final AtomicLong stuckPayments = new AtomicLong();
    private final AtomicLong stuckRefunds = new AtomicLong();
    private final AtomicLong stuckSettlements = new AtomicLong();
    private final AtomicLong manualReviewPayments = new AtomicLong();
    private final AtomicLong manualReviewRefunds = new AtomicLong();
    private final AtomicLong overdueRefundRequests = new AtomicLong();

    public PendingSettlementScheduler(PaymentService paymentService, SettlementService settlementService,
                                      MeterRegistry meterRegistry) {
        this.paymentService = paymentService;
        this.settlementService = settlementService;
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
                .description("자동 처리를 멈추고 운영자 확인을 기다리는 건수")
                .tag("type", "payment")
                .register(meterRegistry);
        Gauge.builder("payment.settlement.manual_review", manualReviewRefunds, AtomicLong::get)
                .description("자동 처리를 멈추고 운영자 확인을 기다리는 건수")
                .tag("type", "refund")
                .register(meterRegistry);
        Gauge.builder("payment.refund_request.overdue", overdueRefundRequests, AtomicLong::get)
                .description("고객이 신청한 뒤 3일 넘게 가맹점이 처리하지 않은 환불 신청 건수")
                .register(meterRegistry);
    }

    /** 한 주기가 실패해도 다음 주기는 돌도록 예외를 로그로만 남긴다. 결제 복구가 실패해도 환불 복구는 돈다 */
    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void recover() {
        Instant now = Instant.now();
        try {
            paymentService.recoverPayments(now, BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("결제 복구 주기가 실패했습니다.", e);
        }
        try {
            paymentService.recoverRefunds(now, BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("환불 복구 주기가 실패했습니다.", e);
        }
        try {
            settlementService.recoverSettlements(now, BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("정산 복구 주기가 실패했습니다.", e);
        }
        try {
            reportStuck(now.minus(STUCK_AFTER));
            manualReviewPayments.set(paymentService.countManualReviewPayments());
            manualReviewRefunds.set(paymentService.countManualReviewRefunds());
            overdueRefundRequests.set(paymentService.countOverdueRefundRequests());
        } catch (RuntimeException e) {
            log.error("확정되지 않은 결제·환불 수를 조회하지 못했습니다.", e);
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

package com.travelpay.payment.presentation.payment;

import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.StuckSettlementResult;
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
 * 결과를 모른 채 남은 결제·환불을 주기적으로 원장에 다시 물어 확정
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "payment.settlement-recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingSettlementScheduler {

    // 첫 요청이 아직 원장을 기다리는 중일 수 있으므로, 원장 응답 시간보다 넉넉히 지난 건만 다룸
    private static final Duration STALE_AFTER = Duration.ofMinutes(1);
    private static final int BATCH_SIZE = 100;

    // 요청 후 30분이 지나도 확정되지 않으면 운영자가 봐야 할 대상으로 본다.
    private static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    // 로그에 남길 ID 개수. 로그 한 줄이 너무 길어지지 않게 오래된 순 10개만.
    private static final int STUCK_SAMPLE_SIZE = 10;

    private final PaymentService paymentService;

    private final AtomicLong stuckPayments = new AtomicLong();
    private final AtomicLong stuckRefunds = new AtomicLong();

    public PendingSettlementScheduler(PaymentService paymentService, MeterRegistry meterRegistry) {
        this.paymentService = paymentService;
        Gauge.builder("payment.settlement.stuck", stuckPayments, AtomicLong::get)
                .description("요청 후 30분이 지나도 확정되지 않은 건수")
                .tag("type", "payment")
                .register(meterRegistry);
        Gauge.builder("payment.settlement.stuck", stuckRefunds, AtomicLong::get)
                .description("요청 후 30분이 지나도 확정되지 않은 건수")
                .tag("type", "refund")
                .register(meterRegistry);
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void settleStale() {
        Instant now = Instant.now();
        paymentService.settleStalePayments(now.minus(STALE_AFTER), BATCH_SIZE);
        paymentService.settleStaleRefunds(now.minus(STALE_AFTER), BATCH_SIZE);
        reportStuck(now.minus(STUCK_AFTER));
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
    }
}

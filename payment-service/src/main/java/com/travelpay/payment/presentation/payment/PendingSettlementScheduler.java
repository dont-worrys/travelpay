package com.travelpay.payment.presentation.payment;

import com.travelpay.payment.application.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;

/**
 * 결과를 모른 채 남은 결제·환불을 주기적으로 원장에 다시 물어 확정
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "payment.settlement-recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PendingSettlementScheduler {

    // 첫 요청이 아직 원장을 기다리는 중일 수 있으므로, 원장 응답 시간보다 넉넉히 지난 건만 다룸
    private static final Duration STALE_AFTER = Duration.ofMinutes(1);
    private static final int BATCH_SIZE = 100;

    private final PaymentService paymentService;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void settleStale() {
        Instant before = Instant.now().minus(STALE_AFTER);
        paymentService.settleStalePayments(before, BATCH_SIZE);
        paymentService.settleStaleRefunds(before, BATCH_SIZE);
    }
}

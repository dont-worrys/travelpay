package com.travelpay.exchange.presentation.order;

import com.travelpay.exchange.application.order.ExchangeOrderBacklogMonitor;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 결과를 모른 채 남은 환전 주문을 주기적으로 원장에 다시 보냅니다.
 * 주기는 가장 짧은 재시도 대기 시간(30초)과 같고, 주문별 대기 시간은 대상 조회가 거릅니다.
 * exchange.order.recovery.enabled=false이면 돌지 않습니다. (테스트·수동 확인용)
 * 주기마다 복구보다 먼저 주문 적체(처리 정체·사람 확인 대기) 수를 세어 지표를 갱신합니다.
 * 복구가 오래 걸려도(최대 50건 × 원장 호출) 지표가 늦어지지 않게 먼저 하고, 세기가 실패해도 복구는 계속합니다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "exchange.order.recovery", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ExchangeOrderRecoveryScheduler {

    private final ExchangeOrderRecoveryService recoveryService;
    private final ExchangeOrderBacklogMonitor backlogMonitor;
    private final int batchSize;

    /** 배치 크기가 1보다 작으면 서버 시작 단계에서 실패합니다. */
    public ExchangeOrderRecoveryScheduler(ExchangeOrderRecoveryService recoveryService,
                                          ExchangeOrderBacklogMonitor backlogMonitor,
                                          @Value("${exchange.order.recovery.batch-size:50}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalStateException("exchange.order.recovery.batch-size는 1 이상이어야 합니다: " + batchSize);
        }
        this.recoveryService = recoveryService;
        this.backlogMonitor = backlogMonitor;
        this.batchSize = batchSize;
    }

    /** 한 주기가 실패해도 다음 주기는 돌도록 예외를 로그로만 남깁니다. */
    @Scheduled(initialDelayString = "${exchange.order.recovery.fixed-delay:30s}",
            fixedDelayString = "${exchange.order.recovery.fixed-delay:30s}")
    public void recover() {
        backlogMonitor.refresh(); // 예외를 던지지 않음 (실패하면 이전 값 유지)
        try {
            recoveryService.recover(batchSize);
        } catch (RuntimeException e) {
            log.error("환전 주문 복구 주기 실패: exception={}", e.getClass().getName(), e);
        }
    }
}

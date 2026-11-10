package com.travelpay.exchange.infrastructure.config;

import com.travelpay.exchange.application.order.ExchangeOrderBacklogMonitor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * 주문 적체 모니터의 값을 Prometheus 게이지로 내보냅니다. 게이지는 모니터가 세어 둔 값만 읽어 DB를 조회하지 않습니다.
 * Micrometer는 인프라 계층에 두고, 애플리케이션 계층(모니터)은 숫자만 압니다.
 *
 * - exchange_order_processing_stuck: 신청 후 30분 넘게 PROCESSING인 주문 수 (원장 장애 신호)
 * - exchange_order_manual_review: 사람 확인(MANUAL_REVIEW)을 기다리는 주문 수 (관리자 처리 필요)
 *
 * 값은 복구 스케줄러가 주기(30초)마다 갱신하므로, 복구 스케줄러를 끄면(exchange.order.recovery.enabled=false) 갱신되지 않습니다.
 */
@Component
public class ExchangeOrderBacklogMetrics implements MeterBinder {

    static final String PROCESSING_STUCK = "exchange.order.processing.stuck";
    static final String MANUAL_REVIEW = "exchange.order.manual_review";

    private final ExchangeOrderBacklogMonitor monitor;

    public ExchangeOrderBacklogMetrics(ExchangeOrderBacklogMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PROCESSING_STUCK, monitor, ExchangeOrderBacklogMonitor::stuckProcessingCount)
                .description("신청 후 30분 넘게 결과를 확정하지 못한 PROCESSING 환전 주문 수")
                .register(registry);
        Gauge.builder(MANUAL_REVIEW, monitor, ExchangeOrderBacklogMonitor::manualReviewCount)
                .description("사람 확인(MANUAL_REVIEW)을 기다리는 환전 주문 수")
                .register(registry);
    }
}

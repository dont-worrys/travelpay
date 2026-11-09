package com.travelpay.exchange.infrastructure.config;

import com.travelpay.exchange.application.order.ExchangeOrderBacklogMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeOrderBacklogMetricsTest {

    /** 게이지는 모니터가 세어 둔 값을 읽을 때마다 그대로 보여 줍니다. */
    @Test
    void gaugesReadCurrentMonitorValues() {
        ExchangeOrderBacklogMonitor monitor = mock(ExchangeOrderBacklogMonitor.class);
        when(monitor.stuckProcessingCount()).thenReturn(3L, 0L);
        when(monitor.manualReviewCount()).thenReturn(2L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ExchangeOrderBacklogMetrics(monitor).bindTo(registry);

        assertEquals(3.0, registry.get(ExchangeOrderBacklogMetrics.PROCESSING_STUCK).gauge().value());
        assertEquals(0.0, registry.get(ExchangeOrderBacklogMetrics.PROCESSING_STUCK).gauge().value());
        assertEquals(2.0, registry.get(ExchangeOrderBacklogMetrics.MANUAL_REVIEW).gauge().value());
    }
}

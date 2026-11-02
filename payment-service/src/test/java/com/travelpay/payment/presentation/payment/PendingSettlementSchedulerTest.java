package com.travelpay.payment.presentation.payment;

import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.StuckSettlementResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PendingSettlementSchedulerTest {

    private PaymentService paymentService;
    private SimpleMeterRegistry meterRegistry;
    private PendingSettlementScheduler scheduler;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new PendingSettlementScheduler(paymentService, meterRegistry);
    }

    @Test
    void publishesStuckCountsAsGauges() {
        when(paymentService.findStuckSettlements(any(), anyInt()))
                .thenReturn(new StuckSettlementResult(3, List.of(UUID.randomUUID()), 1, List.of(UUID.randomUUID())));

        scheduler.settleStale();

        assertEquals(3.0, gauge("payment"));
        assertEquals(1.0, gauge("refund"));

        verify(paymentService).findStuckSettlements(any(), eq(10));
    }

    @Test
    void resetsGaugesWhenNothingIsStuck() {
        when(paymentService.findStuckSettlements(any(), anyInt()))
                .thenReturn(new StuckSettlementResult(3, List.of(), 1, List.of()))
                .thenReturn(new StuckSettlementResult(0, List.of(), 0, List.of()));

        scheduler.settleStale();
        scheduler.settleStale();

        assertEquals(0.0, gauge("payment"));
        assertEquals(0.0, gauge("refund"));
    }

    private double gauge(String type) {
        return meterRegistry.get("payment.settlement.stuck").tag("type", type).gauge().value();
    }
}

package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService.RecoveryResult;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeOrderLedgerProcessor ledgerProcessor = mock(ExchangeOrderLedgerProcessor.class);
    private final ExchangeOrderRecoveryService service = new ExchangeOrderRecoveryService(orderRepository,
            ledgerProcessor, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    @Test
    void processesEachTargetAndCountsOutcome() {
        List<ExchangeOrder> targets = orders(3);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        when(ledgerProcessor.process(targets.get(0))).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));
        when(ledgerProcessor.process(targets.get(1))).thenReturn(withStatus(ExchangeOrderStatus.PROCESSING));
        when(ledgerProcessor.process(targets.get(2))).thenReturn(withStatus(ExchangeOrderStatus.MANUAL_REVIEW));

        assertEquals(new RecoveryResult(3, 2, 1, 0), service.recover(10));
    }

    @Test
    void stopsAfterThreeConsecutiveUnsettled() {
        List<ExchangeOrder> targets = orders(5);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.PROCESSING));

        RecoveryResult result = service.recover(10);

        assertEquals(new RecoveryResult(5, 0, 3, 0), result);
        verify(ledgerProcessor, times(3)).process(any());
    }

    @Test
    void settledOrderResetsConsecutiveCount() {
        List<ExchangeOrder> targets = orders(5);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        ExchangeOrder processing = withStatus(ExchangeOrderStatus.PROCESSING);
        when(ledgerProcessor.process(any())).thenReturn(processing, processing,
                withStatus(ExchangeOrderStatus.COMPLETED), processing, processing);

        assertEquals(new RecoveryResult(5, 1, 4, 0), service.recover(10));
        verify(ledgerProcessor, times(5)).process(any());
    }

    @Test
    void errorOnOneOrderDoesNotStopOthers() {
        List<ExchangeOrder> targets = orders(2);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        when(ledgerProcessor.process(targets.get(0))).thenThrow(new IllegalStateException("db down"));
        when(ledgerProcessor.process(targets.get(1))).thenReturn(withStatus(ExchangeOrderStatus.FAILED));

        assertEquals(new RecoveryResult(2, 1, 0, 1), service.recover(10));
    }

    @Test
    void nothingToRecover() {
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(List.of());

        assertEquals(new RecoveryResult(0, 0, 0, 0), service.recover(10));
    }

    private static List<ExchangeOrder> orders(int count) {
        List<ExchangeOrder> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(withStatus(ExchangeOrderStatus.PROCESSING));
        }
        return orders;
    }

    private static ExchangeOrder withStatus(ExchangeOrderStatus status) {
        Instant issuedAt = NOW.minusSeconds(120);
        ExchangeQuote quote = OrderFixtures.wonToYenQuote(UUID.randomUUID(),
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), issuedAt);
        ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(quote, "createdAt", issuedAt);
        ExchangeOrder order = OrderFixtures.placed(quote, UUID.randomUUID(), "key-" + UUID.randomUUID());
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }
}

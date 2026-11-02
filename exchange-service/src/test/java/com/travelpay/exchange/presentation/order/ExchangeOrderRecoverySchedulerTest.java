package com.travelpay.exchange.presentation.order;

import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderRecoverySchedulerTest {

    private final ExchangeOrderRecoveryService recoveryService = mock(ExchangeOrderRecoveryService.class);

    @Test
    void recoversWithConfiguredBatchSize() {
        new ExchangeOrderRecoveryScheduler(recoveryService, 20).recover();

        verify(recoveryService).recover(20);
    }

    @Test
    void failedCycleDoesNotStopScheduling() {
        when(recoveryService.recover(50)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> new ExchangeOrderRecoveryScheduler(recoveryService, 50).recover());
    }

    @Test
    void rejectsBatchSizeBelowOne() {
        assertThrows(IllegalStateException.class, () -> new ExchangeOrderRecoveryScheduler(recoveryService, 0));
    }
}

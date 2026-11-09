package com.travelpay.exchange.presentation.order;

import com.travelpay.exchange.application.order.ExchangeOrderBacklogMonitor;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderRecoverySchedulerTest {

    private final ExchangeOrderRecoveryService recoveryService = mock(ExchangeOrderRecoveryService.class);
    private final ExchangeOrderBacklogMonitor backlogMonitor = mock(ExchangeOrderBacklogMonitor.class);

    @Test
    void recoversWithConfiguredBatchSize() {
        new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 20).recover();

        verify(recoveryService).recover(20);
    }

    /** 적체 지표는 복구보다 먼저 갱신합니다. 복구가 오래 걸려도 지표가 늦어지지 않게 하려는 것입니다. */
    @Test
    void refreshesBacklogBeforeRecovering() {
        new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 20).recover();

        InOrder order = inOrder(backlogMonitor, recoveryService);
        order.verify(backlogMonitor).refresh();
        order.verify(recoveryService).recover(20);
    }

    @Test
    void failedCycleDoesNotStopScheduling() {
        when(recoveryService.recover(50)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 50).recover());
        verify(backlogMonitor).refresh();
    }

    @Test
    void rejectsBatchSizeBelowOne() {
        assertThrows(IllegalStateException.class,
                () -> new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 0));
    }
}

package com.travelpay.exchange.presentation.rate;

import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExchangeRateCollectionSchedulerTest {

    private final ExchangeRateCollector collector = mock(ExchangeRateCollector.class);
    private final ExchangeRateCollectionScheduler scheduler =
            new ExchangeRateCollectionScheduler(collector, mock(RateCollectionPolicy.class));

    @Test
    void startupCollectionFailureDoesNotStopApplication() {
        doThrow(new DataAccessResourceFailureException("db down")).when(collector).collectLatestIfEmpty();

        assertDoesNotThrow(scheduler::collectOnStartup);
        verify(collector).collectLatestIfEmpty();
    }
}

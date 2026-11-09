package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderBacklogMonitorTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-11-09T02:30:00Z");

    private final ExchangeOrderRepository repository = mock(ExchangeOrderRepository.class);
    private final ExchangeOrderBacklogMonitor monitor =
            new ExchangeOrderBacklogMonitor(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void startsAtZeroBeforeFirstRefresh() {
        assertEquals(0, monitor.stuckProcessingCount());
        assertEquals(0, monitor.manualReviewCount());
    }

    /** 정체 기준은 서비스 시계의 지금 - 30분입니다. */
    @Test
    void countsStuckOrdersRequestedAtLeast30MinutesAgoAndManualReviews() {
        when(repository.countProcessingRequestedAtOrBefore(CUTOFF)).thenReturn(3L);
        when(repository.findProcessingIdsRequestedAtOrBefore(CUTOFF, ExchangeOrderBacklogMonitor.STUCK_SAMPLE_SIZE))
                .thenReturn(List.of(UUID.randomUUID()));
        when(repository.countManualReview()).thenReturn(2L);

        monitor.refresh();

        assertEquals(3, monitor.stuckProcessingCount());
        assertEquals(2, monitor.manualReviewCount());
        verify(repository).findProcessingIdsRequestedAtOrBefore(CUTOFF, ExchangeOrderBacklogMonitor.STUCK_SAMPLE_SIZE);
    }

    /** 정체가 없으면 로그용 ID를 조회하지 않습니다. */
    @Test
    void doesNotLookUpSampleIdsWhenNothingIsStuck() {
        when(repository.countProcessingRequestedAtOrBefore(CUTOFF)).thenReturn(0L);

        monitor.refresh();

        verify(repository, never()).findProcessingIdsRequestedAtOrBefore(any(), anyInt());
    }

    /** 세기에 실패하면 0이 아니라 이전 값을 유지합니다. DB 장애를 "적체 없음"으로 보이게 하지 않으려는 것입니다. */
    @Test
    void keepsPreviousValuesWhenCountingFails() {
        when(repository.countProcessingRequestedAtOrBefore(CUTOFF)).thenReturn(4L);
        when(repository.countManualReview()).thenReturn(1L);
        monitor.refresh();

        when(repository.countProcessingRequestedAtOrBefore(CUTOFF)).thenThrow(new DataAccessResourceFailureException("db down"));
        when(repository.countManualReview()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(monitor::refresh);
        assertEquals(4, monitor.stuckProcessingCount());
        assertEquals(1, monitor.manualReviewCount());
    }

    /** 한쪽이 실패해도 다른 쪽은 셉니다. */
    @Test
    void oneFailedCountDoesNotStopTheOther() {
        when(repository.countProcessingRequestedAtOrBefore(CUTOFF)).thenThrow(new DataAccessResourceFailureException("db down"));
        when(repository.countManualReview()).thenReturn(5L);

        monitor.refresh();

        assertEquals(0, monitor.stuckProcessingCount());
        assertEquals(5, monitor.manualReviewCount());
    }

    /** 정체 수는 셌는데 로그용 ID 조회만 실패해도, 센 값은 반영합니다. */
    @Test
    void keepsCountedValueWhenSampleLookupFails() {
        when(repository.countProcessingRequestedAtOrBefore(CUTOFF)).thenReturn(2L);
        when(repository.findProcessingIdsRequestedAtOrBefore(any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(monitor::refresh);
        assertEquals(2, monitor.stuckProcessingCount());
    }
}

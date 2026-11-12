package com.travelpay.exchange.application.reservation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 예약 체결을 체결 전용 스레드에 넘기고, 부른 스레드(환율 수집)는 원장을 기다리지 않는지 확인합니다(#282). */
class AsyncReservationDispatcherTest {

    private static final ExchangeRate YEN = QuoteFixtures.savedRate(ForeignCurrency.JPY, "820.00");

    private final ExchangeReservationExecutor executor = mock(ExchangeReservationExecutor.class);
    private final AsyncReservationDispatcher dispatcher = new AsyncReservationDispatcher(executor);
    /** 원장이 응답하지 않는 것처럼 체결을 붙잡아 두는 문 */
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        dispatcher.shutdown();
    }

    /** 체결이 오래 걸려도 넘기는 쪽은 바로 돌아오고, 체결은 체결 전용 스레드에서 한다 */
    @Test
    void returnsImmediatelyAndExecutesOnWorkerThread() throws Exception {
        UUID id = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        when(executor.execute(id, YEN)).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            started.countDown();
            release.await();
            return ReservationExecution.of(id, ReservationExecution.Outcome.SKIPPED);
        });

        long begin = System.nanoTime();
        dispatcher.dispatch(List.of(new ReservationMatch(YEN, List.of(id))));
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue(started.await(3, TimeUnit.SECONDS), "체결이 시작되지 않았습니다");
        assertTrue(tookMillis < 1_000, "넘기는 쪽이 체결을 기다렸습니다: " + tookMillis + "ms");
        assertEquals(AsyncReservationDispatcher.THREAD_NAME, threadName.get());
    }

    /** 앞 체결이 진행 중이면 이번 목록은 넘기지 않는다. 그 예약은 대기로 남아 다음 새 환율 때 다시 매칭된다 */
    @Test
    void dropsMatchesWhileWorkerIsBusy() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        when(executor.execute(first, YEN)).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return ReservationExecution.of(first, ReservationExecution.Outcome.SKIPPED);
        });
        dispatcher.dispatch(List.of(new ReservationMatch(YEN, List.of(first))));
        assertTrue(started.await(3, TimeUnit.SECONDS), "체결이 시작되지 않았습니다");

        dispatcher.dispatch(List.of(new ReservationMatch(YEN, List.of(second))));
        release.countDown();
        dispatcher.shutdown();

        verify(executor, never()).execute(eq(second), any());
    }

    /** 예약마다 그 예약을 찾은 환율로 체결하고, 하나가 예상 밖 오류로 실패해도 나머지는 계속 체결한다 */
    @Test
    void executesEachReservationWithItsRateAndContinuesAfterFailure() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AsyncReservationDispatcher withOwnWorker = new AsyncReservationDispatcher(executor, worker);
        ExchangeRate yuan = QuoteFixtures.savedRate(ForeignCurrency.CNY, "190.00");
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(executor.execute(failing, YEN)).thenThrow(new IllegalStateException("db down"));
        when(executor.execute(next, YEN)).thenReturn(ReservationExecution.of(next, ReservationExecution.Outcome.SKIPPED));
        when(executor.execute(other, yuan)).thenReturn(ReservationExecution.of(other, ReservationExecution.Outcome.SKIPPED));

        withOwnWorker.dispatch(List.of(new ReservationMatch(YEN, List.of(failing, next)),
                new ReservationMatch(yuan, List.of(other))));
        worker.shutdown();
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS), "체결이 끝나지 않았습니다");

        verify(executor).execute(next, YEN);
        verify(executor).execute(other, yuan);
    }
}

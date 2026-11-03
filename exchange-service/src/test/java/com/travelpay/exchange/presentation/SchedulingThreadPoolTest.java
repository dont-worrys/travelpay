package com.travelpay.exchange.presentation;

import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import com.travelpay.exchange.presentation.order.ExchangeOrderRecoveryScheduler;
import com.travelpay.exchange.presentation.rate.ExchangeRateCollectionScheduler;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.PeriodicTrigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 환율 수집과 주문 복구가 스케줄러 스레드를 나눠 써도, 복구가 오래 걸릴 때 수집이 멈추지 않는지 확인합니다.
 * 스케줄러 두 개와 스프링 스케줄링 자동 설정만 띄우고, 복구는 원장이 응답하지 않는 상황처럼 일부러 붙잡아 둡니다.
 */
class SchedulingThreadPoolTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private final ExchangeRateCollector collector = mock(ExchangeRateCollector.class);
    private final RateCollectionPolicy policy = mock(RateCollectionPolicy.class);
    private final ExchangeOrderRecoveryService recoveryService = mock(ExchangeOrderRecoveryService.class);

    private final AtomicInteger collections = new AtomicInteger();
    private final CountDownLatch recoveryStarted = new CountDownLatch(1);
    private final CountDownLatch releaseRecovery = new CountDownLatch(1);

    @Test
    void collectionKeepsRunningWhileRecoveryIsSlow() {
        int poolSize = configuredPoolSize("application.yml");

        runner(poolSize).run(context -> {
            try {
                assertTrue(recoveryStarted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "복구가 시작되지 않음");
                int beforeWaiting = collections.get();

                assertTrue(eventually(() -> collections.get() >= beforeWaiting + 3),
                        "복구가 붙잡혀 있는 동안 환율 수집이 멈춤 (pool size " + poolSize + ")");
            } finally {
                releaseRecovery.countDown();
            }
        });
    }

    /** 위 테스트가 실제로 스레드 수에 따라 갈리는지 확인합니다. 스레드가 하나면 복구가 끝날 때까지 수집이 멈춥니다. */
    @Test
    void singleThreadStopsCollectionWhileRecoveryIsSlow() {
        runner(1).run(context -> {
            try {
                assertTrue(recoveryStarted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "복구가 시작되지 않음");
                int beforeWaiting = collections.get();

                Thread.sleep(500);

                assertEquals(beforeWaiting, collections.get(), "스레드 하나인데 복구 중에 수집이 실행됨");
            } finally {
                releaseRecovery.countDown();
            }
        });
    }

    private ApplicationContextRunner runner(int poolSize) {
        when(policy.trigger()).thenReturn(new PeriodicTrigger(Duration.ofMillis(50)));
        doAnswer(invocation -> collections.incrementAndGet()).when(collector).collectIfNeeded();
        // 원장이 응답하지 않아 복구 한 번이 오래 걸리는 상황. 검증이 끝날 때까지 풀리지 않게 시간 제한 없이 기다린다.
        // (제한이 있으면 테스트 스레드가 늦게 깨어났을 때 복구가 먼저 풀려, 스레드 하나로도 수집이 돌아 거짓 통과할 수 있음)
        // 테스트의 finally가 항상 풀어 준다
        doAnswer(invocation -> {
            recoveryStarted.countDown();
            releaseRecovery.await();
            return new ExchangeOrderRecoveryService.RecoveryResult(1, 0, 1, 0);
        }).when(recoveryService).recover(anyInt());

        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(ExchangeRateCollectionScheduler.class, ExchangeOrderRecoveryScheduler.class)
                .withBean(ExchangeRateCollector.class, () -> collector)
                .withBean(RateCollectionPolicy.class, () -> policy)
                .withBean(ExchangeOrderRecoveryService.class, () -> recoveryService)
                .withPropertyValues(
                        "spring.task.scheduling.pool.size=" + poolSize,
                        "exchange.order.recovery.fixed-delay=10ms");
    }

    private static int configuredPoolSize(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        return Integer.parseInt(yaml.getObject().getProperty("spring.task.scheduling.pool.size", "1"));
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}

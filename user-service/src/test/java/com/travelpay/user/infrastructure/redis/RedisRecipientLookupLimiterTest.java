package com.travelpay.user.infrastructure.redis;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.travelpay.user.common.config.RecipientLookupProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRecipientLookupLimiterTest {

    private static final UUID REQUESTER = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID OTHER_REQUESTER = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");
    private static final Duration PERIOD = Duration.ofSeconds(60);
    private static final String PREFIX = "travelpay:user:recipient-lookup:";

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        container = new GenericContainer<>(DockerImageName.parse("redis:8.2.11-bookworm"))
                .withExposedPorts(6379).withCommand("redis-server", "--appendonly", "no", "--save", "");
        container.start();
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1))
                .shutdownTimeout(Duration.ofMillis(100)).build();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)), client);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void clearRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void sixtyLookupsAreAllowedAndRejectedLookupsDoNotGrowTheLog() {
        var limiter = limiter(60, PERIOD);

        for (int request = 0; request < 60; request++) {
            assertTrue(limiter.tryAcquire(REQUESTER));
        }
        for (int request = 0; request < 10; request++) {
            assertFalse(limiter.tryAcquire(REQUESTER));
        }

        assertEquals(60L, redis.opsForZSet().zCard(key(REQUESTER)));
    }

    @Test
    void theLargestExactlyRepresentablePeriodStillCreatesAnExpiringLog() {
        var period = Duration.ofMillis(9_007_199_254_740_992L);
        var limiter = limiter(1, period);

        assertTrue(limiter.tryAcquire(REQUESTER));

        long remaining = redis.getExpire(key(REQUESTER), TimeUnit.MILLISECONDS);
        assertTrue(remaining > 0 && remaining <= period.toMillis());
    }

    @Test
    void eachRequesterHasAnIndependentAllowance() {
        var limiter = limiter(1, PERIOD);

        assertTrue(limiter.tryAcquire(REQUESTER));
        assertFalse(limiter.tryAcquire(REQUESTER));
        assertTrue(limiter.tryAcquire(OTHER_REQUESTER));
        assertFalse(limiter.tryAcquire(OTHER_REQUESTER));
    }

    @Test
    void differentLimiterInstancesShareTheSameAllowance() {
        var first = limiter(2, PERIOD);
        var second = limiter(2, PERIOD);

        assertTrue(first.tryAcquire(REQUESTER));
        assertTrue(second.tryAcquire(REQUESTER));
        assertFalse(first.tryAcquire(REQUESTER));
        assertFalse(second.tryAcquire(REQUESTER));
    }

    @Test
    void concurrentLookupsAcrossInstancesCannotExceedTheAllowance() throws Exception {
        var first = limiter(5, PERIOD);
        var second = limiter(5, PERIOD);
        var ready = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int request = 0; request < 20; request++) {
            var limiter = request % 2 == 0 ? first : second;
            tasks.add(() -> {
                ready.await();
                return limiter.tryAcquire(REQUESTER);
            });
        }

        int allowed = 0;
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            var results = tasks.stream().map(executor::submit).toList();
            ready.countDown();
            for (var result : results) {
                allowed += result.get(5, TimeUnit.SECONDS) ? 1 : 0;
            }
        }

        assertEquals(5, allowed);
        assertEquals(5L, redis.opsForZSet().zCard(key(REQUESTER)));
    }

    @Test
    void expiredCallsAreRemovedWhileRecentCallsStillCount() {
        var limiter = limiter(2, PERIOD);
        long now = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        redis.opsForZSet().add(key(REQUESTER), "expired", now - PERIOD.toMillis() - 1000);
        redis.opsForZSet().add(key(REQUESTER), "recent", now - 1000);
        redis.expire(key(REQUESTER), PERIOD);

        assertTrue(limiter.tryAcquire(REQUESTER));
        assertFalse(limiter.tryAcquire(REQUESTER));
        assertEquals(2L, redis.opsForZSet().zCard(key(REQUESTER)));
        assertNull(redis.opsForZSet().score(key(REQUESTER), "expired"));
        assertNotNull(redis.opsForZSet().score(key(REQUESTER), "recent"));
    }

    @Test
    void theAllowanceReturnsWhenTheLogExpires() {
        var limiter = limiter(1, Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire(REQUESTER));
        assertFalse(limiter.tryAcquire(REQUESTER));

        await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(25))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(key(REQUESTER))));

        assertTrue(limiter.tryAcquire(REQUESTER));
    }

    @Test
    void aRejectedLookupDoesNotExtendTheLogsLifetime() {
        var limiter = limiter(1, PERIOD);
        assertTrue(limiter.tryAcquire(REQUESTER));
        redis.expire(key(REQUESTER), Duration.ofSeconds(10));
        long before = redis.getExpire(key(REQUESTER), TimeUnit.MILLISECONDS);

        assertFalse(limiter.tryAcquire(REQUESTER));

        long after = redis.getExpire(key(REQUESTER), TimeUnit.MILLISECONDS);
        assertTrue(after > 0 && after <= before);
    }

    @Test
    void aNullRequesterIsRejectedWithoutCreatingAnAllowance() {
        var limiter = limiter(1, PERIOD);

        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(null));
        assertTrue(redis.keys(PREFIX + "*").isEmpty());
    }

    @Test
    void aWrongTypeRedisKeyIsNotTreatedAsANewAllowance() {
        var limiter = limiter(1, PERIOD);
        redis.opsForValue().set(key(REQUESTER), "unreadable-counter");

        assertThrows(DataAccessException.class, () -> limiter.tryAcquire(REQUESTER));
    }

    @Test
    void aRedisWriteFailureIsThrownInsteadOfAllowingTheLookup() throws Exception {
        var limiter = limiter(1, PERIOD);
        configureRedis("maxmemory-policy", "noeviction");
        configureRedis("maxmemory", "1");
        try {
            assertThrows(DataAccessException.class, () -> limiter.tryAcquire(REQUESTER));
        }
        finally {
            configureRedis("maxmemory", "0");
        }
        assertTrue(redis.keys(PREFIX + "*").isEmpty());
    }

    @Test
    void aRedisTimeoutIsThrownInsteadOfAllowingTheLookup() {
        var limiter = limiter(1, PERIOD);
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
        try {
            assertThrows(DataAccessException.class, () -> limiter.tryAcquire(REQUESTER));
        }
        finally {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
    }

    private static RedisRecipientLookupLimiter limiter(int limit, Duration period) {
        return new RedisRecipientLookupLimiter(redis, new RecipientLookupProperties(limit, period));
    }

    private static String key(UUID requesterId) {
        return PREFIX + requesterId;
    }

    private static void configureRedis(String name, String value) throws IOException, InterruptedException {
        var result = container.execInContainer("redis-cli", "CONFIG", "SET", name, value);
        assertEquals(0, result.getExitCode(), result.getStderr());
        assertEquals("OK", result.getStdout().strip());
    }
}

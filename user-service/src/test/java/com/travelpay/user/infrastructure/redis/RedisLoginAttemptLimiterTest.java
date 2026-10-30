package com.travelpay.user.infrastructure.redis;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.travelpay.user.common.config.LoginAttemptProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisLoginAttemptLimiterTest {

    private static final String EMAIL = "login.member@example.com";
    private static final String IP = "203.0.113.10";
    private static final String OTHER_IP = "203.0.113.20";
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration BLOCK = Duration.ofMinutes(20);
    private static final Duration IN_FLIGHT = Duration.ofMinutes(1);
    private static final String KEYS = "travelpay:user:login-attempt:";

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
        connectionFactory.destroy();
        container.stop();
    }

    @BeforeEach
    void clearRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void fiveFailuresFromTheSameEmailAndIpBlockOnlyThatPairForTheBlockTime() {
        var limiter = limiter(5, 20);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertTrue(limiter.tryStart(EMAIL, IP), "실패 " + (attempt - 1) + "번 뒤의 시도는 허용합니다.");
            limiter.recordFailure(EMAIL, IP);
        }

        assertFalse(limiter.tryStart(EMAIL, IP));
        assertRemaining(single("pair:*:block"), BLOCK);
        assertEquals(Set.of(), keys("pair:*:failures"));
        assertTrue(limiter.tryStart(EMAIL, OTHER_IP), "다른 IP의 같은 이메일은 이메일 한도 전까지 막지 않습니다.");
        assertTrue(limiter.tryStart("other.member@example.com", IP));
    }

    @Test
    void failuresAreCountedForTheWindowStartingAtTheFirstFailure() {
        var limiter = limiter(5, 20);

        limiter.tryStart(EMAIL, IP);
        limiter.recordFailure(EMAIL, IP);
        limiter.tryStart(EMAIL, IP);

        assertEquals("1", redis.opsForValue().get(single("pair:*:failures")));
        assertEquals("1", redis.opsForValue().get(single("pair:*:in-flight")));
        assertRemaining(single("pair:*:failures"), WINDOW);
        assertRemaining(single("account:*:failures"), WINDOW);
    }

    @Test
    void twentyFailuresSpreadOverIpsBlockTheEmailFromEveryIp() {
        var limiter = limiter(5, 20);

        for (int host = 1; host <= 20; host++) {
            assertTrue(limiter.tryStart(EMAIL, "198.51.100." + host));
            limiter.recordFailure(EMAIL, "198.51.100." + host);
        }

        assertFalse(limiter.tryStart(EMAIL, "198.51.100.200"));
        assertRemaining(single("account:*:block"), BLOCK);
        assertTrue(limiter.tryStart("other.member@example.com", IP));
    }

    @Test
    void aSuccessResetsTheEmailAndIpCountButKeepsEarlierFailuresOnTheEmailCount() {
        var limiter = limiter(3, 4);
        fail(limiter, IP, 2);

        assertTrue(limiter.tryStart(EMAIL, IP));
        limiter.recordSuccess(EMAIL, IP);

        // 이메일+IP 횟수가 남아 있었다면 다음 두 번째 시도가 3번째 실패로 막힙니다.
        fail(limiter, IP, 2);
        // 이메일 횟수는 성공 전 실패 2번과 성공 뒤 실패 2번을 합쳐 한도 4에 닿습니다.
        assertFalse(limiter.tryStart(EMAIL, OTHER_IP));
    }

    @Test
    void cancelledAttemptsAreNotCounted() {
        var limiter = limiter(5, 20);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertTrue(limiter.tryStart(EMAIL, IP));
            limiter.cancel(EMAIL, IP);
        }

        assertEquals(Set.of(), keys("*:failures"));
        assertEquals("0", redis.opsForValue().get(single("pair:*:in-flight")));
        assertEquals("0", redis.opsForValue().get(single("account:*:in-flight")));
    }

    @Test
    void overlappingAttemptsThatEndWithoutAFailureDoNotTriggerABlock() {
        var limiter = limiter(5, 20);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertTrue(limiter.tryStart(EMAIL, IP));
        }

        // 겹친 5개 중 1개만 비밀번호가 틀렸고, 나머지는 정지 계정·장애처럼 실패로 세지 않는 결과입니다.
        limiter.recordFailure(EMAIL, IP);
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.cancel(EMAIL, IP);
        }

        assertEquals(Set.of(), keys("*:block"));
        assertTrue(limiter.tryStart(EMAIL, IP));
    }

    @Test
    void aSuccessfulLoginThatOverlapsTheFourthFailureLeavesThePairUnblocked() {
        var limiter = limiter(5, 20);
        fail(limiter, IP, 3);
        assertTrue(limiter.tryStart(EMAIL, IP));
        assertTrue(limiter.tryStart(EMAIL, IP));

        limiter.recordFailure(EMAIL, IP);
        limiter.recordSuccess(EMAIL, IP);

        assertEquals(Set.of(), keys("pair:*:block"));
        assertTrue(limiter.tryStart(EMAIL, IP));
    }

    @Test
    void aFailureThatEndsAfterAnOverlappingSuccessIsStillCounted() {
        var limiter = limiter(2, 20);
        assertTrue(limiter.tryStart(EMAIL, IP));
        assertTrue(limiter.tryStart(EMAIL, IP));

        limiter.recordSuccess(EMAIL, IP);
        limiter.recordFailure(EMAIL, IP);
        fail(limiter, IP, 1);

        assertFalse(limiter.tryStart(EMAIL, IP));
    }

    @Test
    void anAttemptLeftInProgressByAStoppedServerExpires() {
        var limiter = limiter(5, 20);

        limiter.tryStart(EMAIL, IP);

        assertRemaining(single("pair:*:in-flight"), IN_FLIGHT);
        assertRemaining(single("account:*:in-flight"), IN_FLIGHT);
    }

    @Test
    void attemptsInProgressCountTowardTheLimitSoConcurrentRequestsCannotExceedIt() throws Exception {
        var limiter = limiter(5, 20);
        var ready = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int request = 0; request < 20; request++) {
            tasks.add(() -> {
                ready.await();
                return limiter.tryStart(EMAIL, IP);
            });
        }

        int started = 0;
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            var results = tasks.stream().map(executor::submit).toList();
            ready.countDown();
            for (Future<Boolean> result : results) {
                started += result.get() ? 1 : 0;
            }
        }

        assertEquals(5, started);
    }

    @Test
    void afterTheBlockEndsTheEmailAndIpPairStartsFromZero() {
        var limiter = limiter(2, 20);
        fail(limiter, IP, 2);
        assertFalse(limiter.tryStart(EMAIL, IP));

        // 차단 시간이 지난 상태를 만들기 위해 차단 키를 지웁니다.
        redis.delete(single("pair:*:block"));

        fail(limiter, IP, 2);
        assertFalse(limiter.tryStart(EMAIL, IP));
    }

    @Test
    void redisKeysDoNotContainTheEmailOrIp() {
        var limiter = limiter(1, 1);

        limiter.tryStart(EMAIL, IP);
        limiter.recordFailure(EMAIL, IP);

        var keys = keys("*");
        assertEquals(4, keys.size(), keys.toString());
        assertTrue(keys.stream().noneMatch(key -> key.contains("login.member") || key.contains(IP)), keys.toString());
    }

    @Test
    void aRedisWriteFailureIsThrownInsteadOfAllowingTheAttempt() throws Exception {
        var limiter = limiter(5, 20);
        configureRedis("maxmemory-policy", "noeviction");
        configureRedis("maxmemory", "1");
        try {
            assertThrows(DataAccessException.class, () -> limiter.tryStart(EMAIL, IP));
        }
        finally {
            configureRedis("maxmemory", "0");
        }
        assertEquals(Set.of(), keys("*"));
    }

    @Test
    void aRedisTimeoutIsThrownInsteadOfAllowingTheAttempt() {
        var limiter = limiter(5, 20);
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
        try {
            assertThrows(DataAccessException.class, () -> limiter.tryStart(EMAIL, IP));
        }
        finally {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
    }

    private static RedisLoginAttemptLimiter limiter(int pairLimit, int accountLimit) {
        return new RedisLoginAttemptLimiter(redis, new LoginAttemptProperties(pairLimit, accountLimit, WINDOW, BLOCK));
    }

    private static void fail(RedisLoginAttemptLimiter limiter, String ip, int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            assertTrue(limiter.tryStart(EMAIL, ip));
            limiter.recordFailure(EMAIL, ip);
        }
    }

    private static Set<String> keys(String pattern) {
        return redis.keys(KEYS + pattern);
    }

    private static String single(String pattern) {
        var keys = keys(pattern);
        assertEquals(1, keys.size(), keys.toString());
        return keys.iterator().next();
    }

    private static void assertRemaining(String key, Duration expected) {
        var remaining = Duration.ofMillis(redis.getExpire(key, TimeUnit.MILLISECONDS));
        assertTrue(remaining.compareTo(expected) <= 0 && remaining.compareTo(expected.minusSeconds(5)) > 0,
                key + " 남은 시간: " + remaining);
    }

    private static void configureRedis(String key, String value) throws IOException, InterruptedException {
        var result = container.execInContainer("redis-cli", "CONFIG", "SET", key, value);
        assertEquals(0, result.getExitCode(), result.getStderr());
    }
}

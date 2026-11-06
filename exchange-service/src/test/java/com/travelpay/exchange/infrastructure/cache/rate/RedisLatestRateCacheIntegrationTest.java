package com.travelpay.exchange.infrastructure.cache.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Redis(팀 compose와 같은 이미지, 비밀번호 사용)에 환율 캐시를 읽고 씁니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisLatestRateCacheIntegrationTest {

    private static final String PASSWORD = "test-cache-password";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.11-bookworm")
            .withCommand("redis-server", "--requirepass", PASSWORD)
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final RedisLatestRateCache cache = new RedisLatestRateCache(redis);

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setPassword(PASSWORD);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clear() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void usesKeyPerSourceAndCurrency() {
        assertEquals("exchange:rate:latest:MOCK:JPY", RedisLatestRateCache.key(RateSource.MOCK, ForeignCurrency.JPY));
        assertEquals("exchange:rate:latest:KOREAEXIM:CNY", RedisLatestRateCache.key(RateSource.KOREAEXIM, ForeignCurrency.CNY));
    }

    /** ID·소수 자릿수·수집 시각까지 DB에서 읽은 값과 같아야 견적 근거(rateId, 적용 환율)가 달라지지 않습니다. */
    @Test
    void readsBackWholeRate() {
        ExchangeRate jpy = rate(ForeignCurrency.JPY, "2026-11-06T01:00:00.123456Z");
        ExchangeRate cny = rate(ForeignCurrency.CNY, "2026-11-06T01:00:00.123456Z");
        cache.putAll(List.of(jpy, cny));

        Map<ForeignCurrency, ExchangeRate> found = cache.findAll(RateSource.MOCK, List.of(ForeignCurrency.CNY, ForeignCurrency.JPY));

        assertEquals(2, found.size());
        assertSameRate(cny, found.get(ForeignCurrency.CNY));
        assertSameRate(jpy, found.get(ForeignCurrency.JPY));
    }

    /** 수집기가 새 회차를 넣으면 통화마다 이전 값을 덮어씁니다. */
    @Test
    void overwritesPreviousRate() {
        cache.putAll(List.of(rate(ForeignCurrency.JPY, "2026-11-06T01:00:00Z")));
        ExchangeRate newer = rate(ForeignCurrency.JPY, "2026-11-06T01:00:05Z");

        cache.putAll(List.of(newer));

        assertSameRate(newer, cache.find(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow());
    }

    @Test
    void findsOneCurrency() {
        ExchangeRate jpy = rate(ForeignCurrency.JPY, "2026-11-06T01:00:00Z");
        cache.putAll(List.of(jpy));

        assertSameRate(jpy, cache.find(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow());
        assertEquals(Optional.empty(), cache.find(RateSource.MOCK, ForeignCurrency.CNY));
    }

    /** 한 통화가 없어도 다른 통화는 캐시에서 읽습니다. 없는 통화만 빠져 그 통화만 DB에서 읽으면 됩니다. */
    @Test
    void returnsCachedCurrenciesEvenIfOthersAreMissing() {
        ExchangeRate jpy = rate(ForeignCurrency.JPY, "2026-11-06T01:00:00Z");
        cache.putAll(List.of(jpy));

        Map<ForeignCurrency, ExchangeRate> found = cache.findAll(RateSource.MOCK, List.of(ForeignCurrency.JPY, ForeignCurrency.CNY));

        assertEquals(Set.of(ForeignCurrency.JPY), found.keySet());
        assertSameRate(jpy, found.get(ForeignCurrency.JPY));
    }

    @Test
    void returnsNothingForNoCurrencies() {
        assertTrue(cache.findAll(RateSource.MOCK, List.of()).isEmpty());
    }

    /** 다른 모드에서 넣은 값이 섞이지 않습니다. (DB 조회도 현재 모드 출처만 읽음) */
    @Test
    void keepsSourcesApart() {
        cache.putAll(List.of(rate(ForeignCurrency.JPY, "2026-11-06T01:00:00Z")));

        assertEquals(Optional.empty(), cache.find(RateSource.KOREAEXIM, ForeignCurrency.JPY));
    }

    @Test
    void setsTtlOnEveryKey() {
        cache.putAll(List.of(rate(ForeignCurrency.JPY, "2026-11-06T01:00:00Z"), rate(ForeignCurrency.CNY, "2026-11-06T01:00:00Z")));

        for (ForeignCurrency currency : ForeignCurrency.values()) {
            Long ttl = redis.getExpire(RedisLatestRateCache.key(RateSource.MOCK, currency));
            assertTrue(ttl != null && ttl > 0 && ttl <= RedisLatestRateCache.TTL.toSeconds(), currency + " TTL: " + ttl);
        }
    }

    /**
     * 쓰기는 통화별 SET만 보내고 MULTI/EXEC(원자적 실행)는 쓰지 않는지 Redis의 명령 통계로 확인합니다.
     * 한 번에 보내는지(파이프라인)는 명령 수로는 알 수 없어, 결과가 모두 반영됐는지만 함께 봅니다.
     */
    @Test
    void writesEachCurrencyWithSetOnly() {
        resetCommandStats();

        cache.putAll(List.of(rate(ForeignCurrency.JPY, "2026-11-06T01:00:00Z"), rate(ForeignCurrency.CNY, "2026-11-06T01:00:00Z")));

        assertEquals(2, commandCalls("set"));
        assertEquals(0, commandCalls("multi"));
        assertEquals(0, commandCalls("exec"));
        assertEquals(2, cache.findAll(RateSource.MOCK, List.of(ForeignCurrency.values())).size());
    }

    /** 여러 통화 읽기가 통화 수와 관계없이 MGET 한 번(왕복 1번)인지 확인합니다. */
    @Test
    void readsAllCurrenciesWithOneMget() {
        cache.putAll(List.of(rate(ForeignCurrency.JPY, "2026-11-06T01:00:00Z"), rate(ForeignCurrency.CNY, "2026-11-06T01:00:00Z")));
        resetCommandStats();

        cache.findAll(RateSource.MOCK, List.of(ForeignCurrency.values()));

        assertEquals(1, commandCalls("mget"));
        assertEquals(0, commandCalls("get"));
    }

    /** 배포 중 형식이 바뀌는 등 읽을 수 없는 값은 캐시에 없는 것으로 보고 DB 조회로 넘깁니다. */
    @Test
    void treatsUnreadableValueAsMissing() {
        redis.opsForValue().set(RedisLatestRateCache.key(RateSource.MOCK, ForeignCurrency.JPY), "{\"old\":\"format\"}");

        assertEquals(Optional.empty(), cache.find(RateSource.MOCK, ForeignCurrency.JPY));
    }

    private static void resetCommandStats() {
        redis.execute(connection -> {
            connection.serverCommands().resetConfigStats();
            return null;
        }, true);
    }

    /** INFO commandstats의 cmdstat_{명령}:calls=N 값입니다. 한 번도 안 불렸으면 0입니다. */
    private static long commandCalls(String command) {
        Properties stats = redis.execute(connection -> connection.serverCommands().info("commandstats"), true);
        String line = stats == null ? null : stats.getProperty("cmdstat_" + command);
        if (line == null) {
            return 0;
        }
        String calls = line.split(",")[0];
        return Long.parseLong(calls.substring(calls.indexOf('=') + 1));
    }

    private static ExchangeRate rate(ForeignCurrency currency, String collectedAt) {
        boolean jpy = currency == ForeignCurrency.JPY;
        return ExchangeRate.restore(UUID.randomUUID(), new CollectedRate(currency, jpy ? 100 : 1,
                new BigDecimal(jpy ? "906.12340000" : "190.50000000"),
                new BigDecimal(jpy ? "921.98000000" : "200.02000000"),
                new BigDecimal(jpy ? "890.26000000" : "180.98000000"),
                LocalDate.of(2026, 11, 6), RateSource.MOCK), Instant.parse(collectedAt));
    }

    private static void assertSameRate(ExchangeRate expected, ExchangeRate actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getCurrency(), actual.getCurrency());
        assertEquals(expected.getPriceCurrency(), actual.getPriceCurrency());
        assertEquals(expected.getUnit(), actual.getUnit());
        // equals로 비교해 소수 자릿수(scale)까지 같은지 확인합니다.
        assertEquals(expected.getMidRate(), actual.getMidRate());
        assertEquals(expected.getBuyRate(), actual.getBuyRate());
        assertEquals(expected.getSellRate(), actual.getSellRate());
        assertEquals(expected.getSource(), actual.getSource());
        assertEquals(expected.getRateDate(), actual.getRateDate());
        assertEquals(expected.getCollectedAt(), actual.getCollectedAt());
    }
}

package com.travelpay.exchange;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.LatestRateCache;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.infrastructure.cache.rate.RedisLatestRateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 수집기가 DB에 커밋한 환율을 Redis에 넣는지 실제 Postgres·Redis로 확인합니다(기록 문서 5절 시나리오 1~3).
 * mock 모드로 띄우고, 스케줄러는 끈 채 시계를 5초씩 옮기며 수집기를 직접 부릅니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.mode=mock",
        "exchange.rate.collection.enabled=false",
        "exchange.rate.cache.enabled=true",
        "exchange.order.recovery.enabled=false"
})
class ExchangeRateCacheIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final String REDIS_PASSWORD = "test-cache-password";
    private static final Instant NOW = Instant.parse("2026-11-06T02:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.11-bookworm")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Autowired
    private ExchangeRateCollector collector;

    @Autowired
    private LatestRateCache cache;

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ExchangeQuoteIntegrationTest.MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        clock.set(NOW);
    }

    @Test
    void usesRedisCacheWhenEnabled() {
        assertInstanceOf(RedisLatestRateCache.class, cache);
    }

    /** 시나리오 1: 수집 후 DB 최신 행과 Redis 값이 같다 (환율 ID까지) */
    @Test
    void collectionPutsCommittedRatesIntoRedis() {
        collectOnce();

        for (ForeignCurrency currency : ForeignCurrency.values()) {
            ExchangeRate db = repository.findLatest(RateSource.MOCK, currency).orElseThrow();
            ExchangeRate cached = cache.find(RateSource.MOCK, currency).orElseThrow();
            assertEquals(db.getId(), cached.getId(), currency.name());
            assertEquals(0, db.getMidRate().compareTo(cached.getMidRate()), currency.name());
            assertEquals(db.getCollectedAt(), cached.getCollectedAt(), currency.name());
        }
    }

    /** 시나리오 2: 다음 수집 후 Redis도 새 행으로 바뀐다 */
    @Test
    void nextCollectionReplacesRedisValue() {
        collectOnce();
        UUID first = cachedId(ForeignCurrency.JPY);

        collectOnce();

        UUID latestInDb = repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow().getId();
        assertNotEquals(first, latestInDb);
        assertEquals(latestInDb, cachedId(ForeignCurrency.JPY));
    }

    /**
     * 시나리오 3: DB 저장이 실패해 롤백되면 Redis는 이전 값 그대로다 (커밋 후에만 갱신).
     * 새 행을 모두 거절하는 CHECK 제약을 잠시 걸어 실제 저장 실패를 만듭니다.
     */
    @Test
    void rolledBackSaveLeavesRedisUnchanged() {
        collectOnce();
        UUID before = cachedId(ForeignCurrency.JPY);

        jdbcTemplate.execute("ALTER TABLE " + SCHEMA + ".exchange_rates"
                + " ADD CONSTRAINT reject_new_rows CHECK (mid_rate < 0) NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class, this::collectOnce);
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + SCHEMA + ".exchange_rates DROP CONSTRAINT reject_new_rows");
        }

        assertEquals(before, cachedId(ForeignCurrency.JPY));
        assertEquals(before, repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow().getId());
    }

    private void collectOnce() {
        clock.set(clock.instant().plusSeconds(5));
        collector.collectIfNeeded();
    }

    private UUID cachedId(ForeignCurrency currency) {
        Optional<ExchangeRate> cached = cache.find(RateSource.MOCK, currency);
        return cached.orElseThrow().getId();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        ExchangeQuoteIntegrationTest.MutableClock testClock() {
            return new ExchangeQuoteIntegrationTest.MutableClock();
        }
    }
}

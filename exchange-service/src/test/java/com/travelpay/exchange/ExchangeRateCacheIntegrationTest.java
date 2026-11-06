package com.travelpay.exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.application.rate.LatestRateCache;
import com.travelpay.exchange.domain.rate.CollectedRate;
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
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수집기가 DB에 커밋한 환율을 Redis에 넣고(기록 문서 5절 시나리오 1~3), 조회·견적이 그 값을 읽는지(시나리오 4·7) 실제 Postgres·Redis로 확인합니다.
 * 캐시에서 읽었는지 구분하려고, 캐시를 거치지 않고 DB에만 더 새로운 행을 넣은 뒤 어느 행이 나오는지 봅니다.
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
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

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

    @Autowired
    private ExchangeRateQueryService queryService;

    @Autowired
    private WebApplicationContext context;

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

    /** 시나리오 4·통화별 독립: 캐시에 있는 통화는 캐시에서, 캐시에서 지운 통화만 DB에서 읽는다 */
    @Test
    void rateQueryReadsCachedCurrenciesAndOnlyMissingOnesFromDb() {
        collectOnce();
        UUID cachedJpy = cachedId(ForeignCurrency.JPY);
        saveDirectlyToDbOnly(ForeignCurrency.JPY);
        UUID newestCny = saveDirectlyToDbOnly(ForeignCurrency.CNY);
        redis.delete("exchange:rate:latest:MOCK:CNY");

        List<ExchangeRate> latest = queryService.findLatest(List.of(ForeignCurrency.JPY, ForeignCurrency.CNY));

        assertEquals(List.of(ForeignCurrency.JPY, ForeignCurrency.CNY), latest.stream().map(ExchangeRate::getCurrency).toList());
        assertEquals(cachedJpy, latest.get(0).getId(), "JPY는 캐시 값");
        assertEquals(newestCny, latest.get(1).getId(), "CNY만 DB 값");
    }

    /** 시나리오 7: 견적은 캐시에서 읽은 환율 행의 ID와 값을 그대로 남긴다 */
    @Test
    void quoteKeepsRateIdReadFromCache() throws Exception {
        collectOnce();
        ExchangeRate cachedJpy = cache.find(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow();
        saveDirectlyToDbOnly(ForeignCurrency.JPY);

        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER).header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isCreated());

        Map<String, Object> quote = jdbcTemplate.queryForMap("SELECT rate_id, applied_rate FROM " + SCHEMA + ".exchange_quotes");
        assertEquals(cachedJpy.getId(), quote.get("rate_id"));
        assertEquals(0, cachedJpy.getMidRate().compareTo((BigDecimal) quote.get("applied_rate")));
    }

    /** 캐시를 거치지 않고 DB에만 더 새로운 MOCK 행을 넣습니다. 캐시 값과 DB 값을 구분하는 데 씁니다. */
    private UUID saveDirectlyToDbOnly(ForeignCurrency currency) {
        ExchangeRate latest = repository.findLatest(RateSource.MOCK, currency).orElseThrow();
        BigDecimal mid = latest.getMidRate().add(BigDecimal.ONE);
        return repository.save(ExchangeRate.collected(new CollectedRate(currency, latest.getUnit(), mid,
                mid.add(BigDecimal.ONE), mid.subtract(BigDecimal.ONE), latest.getRateDate(), RateSource.MOCK),
                clock.instant().plusMillis(1))).getId();
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

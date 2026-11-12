package com.travelpay.exchange;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.application.rate.LatestRateCache;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.infrastructure.cache.rate.RedisLatestRateCache;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.web.servlet.MockMvc;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
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
     * 시나리오 5: DB 저장은 됐는데 캐시 갱신만 실패하면, 이전 값은 유효 시간(TTL) 동안만 남고 그 뒤에는 DB 값으로 회복된다.
     * Redis는 살아 있고 쓰기만 실패하는 상황을 만들려고, 메모리 상한을 아주 작게 하고 넘치면 쓰기를 거절하게(noeviction) 바꿉니다.
     * TTL(30초)을 기다리는 대신 키 만료를 앞당겨 "TTL이 지난 뒤"를 만듭니다.
     */
    @Test
    void failedCacheUpdateKeepsOldValueOnlyUntilTtlThenRecoversFromDb() {
        collectOnce();
        UUID cachedBefore = cachedId(ForeignCurrency.JPY);

        setRedisConfig("maxmemory-policy", "noeviction");
        setRedisConfig("maxmemory", "1");
        try {
            collectOnce(); // DB 저장은 성공, 캐시 쓰기는 OOM으로 거절됨 (수집은 실패하지 않음)
        } finally {
            setRedisConfig("maxmemory", "0");
        }
        UUID newestInDb = repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow().getId();
        assertNotEquals(cachedBefore, newestInDb, "DB에는 새 회차가 저장됨");

        // 갱신에 실패한 동안은 이전 값이 남아 있다. 남는 시간은 TTL(30초)이 상한이다
        String key = "exchange:rate:latest:MOCK:JPY";
        assertEquals(cachedBefore, queryService.findLatest(ForeignCurrency.JPY).orElseThrow().getId());
        Long ttl = redis.getExpire(key);
        assertTrue(ttl != null && ttl > 0 && ttl <= 30, "이전 값의 남은 수명이 TTL 안: " + ttl);

        // TTL이 지난 뒤: 키가 사라지고 DB의 최신 값으로 응답한다
        redis.expire(key, Duration.ofMillis(1));
        waitUntilGone(key);
        assertEquals(newestInDb, queryService.findLatest(ForeignCurrency.JPY).orElseThrow().getId());
        assertEquals(newestInDb, queryService.findLatest(List.of(ForeignCurrency.JPY)).get(0).getId());
    }

    private void setRedisConfig(String name, String value) {
        redis.execute(connection -> {
            connection.serverCommands().setConfig(name, value);
            return null;
        }, true);
    }

    private void waitUntilGone(String key) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(key))) {
            assertTrue(System.nanoTime() < deadline, "키가 만료되지 않음: " + key);
            Thread.onSpinWait();
        }
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

    /**
     * 견적 발급은 DB 연결을 빌리지 않는다(#223). 환율은 캐시에서, 견적은 Redis에 저장하므로 DB가 필요 없다.
     * HikariCP가 연결을 내준 횟수(acquire)가 발급 전후로 같은지 본다. 이 테스트는 수집·복구 스케줄러를 꺼 두어 다른 DB 사용이 없다.
     * 견적 발급에 트랜잭션이 남아 있으면 쿼리가 없어도 트랜잭션 시작 때 연결을 빌려 이 테스트가 실패한다.
     */
    @Test
    void issuingQuoteDoesNotBorrowDbConnection() throws Exception {
        collectOnce();
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        issueYenQuote(mockMvc); // 첫 요청의 준비 작업을 먼저 끝낸다
        double acquiredBefore = acquiredConnections();

        for (int i = 0; i < 5; i++) {
            issueYenQuote(mockMvc);
        }

        assertEquals(acquiredBefore, acquiredConnections(), "견적 발급 5번 동안 DB 연결을 빌리지 않음");
    }

    private void issueYenQuote(MockMvc mockMvc) throws Exception {
        mockMvc.perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER).header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isCreated());
    }

    /** HikariCP가 지금까지 연결을 내준 횟수입니다. */
    private double acquiredConnections() {
        return meterRegistry.get("hikaricp.connections.acquire").timer().count();
    }

    /** 시나리오 7: 견적은 캐시에서 읽은 환율 행의 ID와 값을 그대로 남긴다 */
    @Test
    void quoteKeepsRateIdReadFromCache() throws Exception {
        collectOnce();
        ExchangeRate cachedJpy = cache.find(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow();
        saveDirectlyToDbOnly(ForeignCurrency.JPY);

        String body = MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER).header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 견적은 Redis에 저장된다(#223). 저장된 견적의 환율 근거가 캐시에서 읽은 환율인지 본다
        String stored = redis.opsForValue().get("exchange:quote:" + JsonPath.read(body, "$.data.quoteId"));
        assertEquals(cachedJpy.getId().toString(), JsonPath.read(stored, "$.rateId"));
        assertEquals(0, cachedJpy.getMidRate().compareTo(new BigDecimal(JsonPath.read(stored, "$.appliedRate").toString())));
    }

    /**
     * 수집과 조회가 동시에 계속 돌 때: 수집(DB 커밋 + 캐시 갱신)이 끝난 뒤 시작한 조회는 그 회차보다 오래된 환율을 받지 않는다.
     * 그리고 조회가 받은 환율은 모두 DB에 커밋된 행이다(롤백된·없는 행이 나가지 않음).
     * 수집 중간(커밋 후 캐시 갱신 전)에 시작한 조회는 직전 회차를 받을 수 있는데, 그 회차도 커밋된 정상 값이라 허용한다.
     */
    @Test
    void readsNeverGoBehindFinishedCollectionWhileCollectingConcurrently() throws Exception {
        collectOnce();
        AtomicReference<Instant> finishedRound = new AtomicReference<>(clock.instant());
        AtomicBoolean collecting = new AtomicBoolean(true);
        Queue<String> violations = new ConcurrentLinkedQueue<>();
        Set<UUID> returnedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger reads = new AtomicInteger();
        double hitsBefore = meterRegistry.get("exchange.rate.cache.lookups").tag("result", "hit").counter().count();

        ExecutorService readers = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) {
                readers.submit(() -> {
                    while (collecting.get()) {
                        Instant mustBeAtLeast = finishedRound.get();
                        for (ExchangeRate rate : queryService.findLatest(List.of(ForeignCurrency.values()))) {
                            returnedIds.add(rate.getId());
                            if (rate.getCollectedAt().isBefore(mustBeAtLeast)) {
                                violations.add(rate.getCurrency() + " " + rate.getCollectedAt() + " < " + mustBeAtLeast);
                            }
                        }
                        reads.incrementAndGet();
                    }
                    return null;
                });
            }
            for (int round = 0; round < 100; round++) {
                collectOnce();
                finishedRound.set(clock.instant());
            }
        } finally {
            collecting.set(false);
            readers.shutdown();
            assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS));
        }

        double hits = meterRegistry.get("exchange.rate.cache.lookups").tag("result", "hit").counter().count() - hitsBefore;
        System.out.println("[정합성] 수집 100회 동안 조회 " + reads.get() + "번, 캐시 hit " + (long) hits + "건, 위반 " + violations.size());
        assertTrue(reads.get() > 100, "수집 중에 조회가 충분히 겹쳐야 의미가 있음: " + reads.get());
        assertTrue(hits > 0, "캐시 경로를 실제로 지나야 함");
        assertEquals(List.of(), List.copyOf(violations));
        Long notInDb = jdbcTemplate.queryForObject("SELECT count(*) FROM unnest(?::uuid[]) AS ids(id) WHERE id NOT IN (SELECT exchange_rate_id FROM "
                + SCHEMA + ".exchange_rates)", Long.class, (Object) returnedIds.toArray(UUID[]::new));
        assertEquals(0L, notInDb, "조회가 준 환율은 모두 DB에 있는 행");
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

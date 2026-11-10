package com.travelpay.exchange;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 캐시 Redis에 장애가 나도 환율 조회·견적 발급·수집이 DB로 계속 동작하는지 실제 Redis 컨테이너를 멈춰 확인합니다(기록 문서 5절 시나리오 6).
 * 두 가지 장애를 차례로 만듭니다. 컨테이너가 같아 순서가 중요하므로 테스트 순서를 고정합니다.
 * 1) 응답 없음(docker pause): 연결은 살아 있지만 Redis가 답하지 않음 → 명령 타임아웃(200ms)까지 기다린 뒤 DB
 * 2) 꺼짐(docker stop): 연결이 끊김 → 기다리지 않고 바로 DB
 * Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.mode=mock",
        "exchange.rate.collection.enabled=false",
        "exchange.rate.cache.enabled=true",
        "exchange.order.recovery.enabled=false"
})
class ExchangeRateCacheOutageIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final String REDIS_PASSWORD = "test-cache-password";
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final List<ForeignCurrency> ALL = List.of(ForeignCurrency.values());

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
    private ExchangeRateQueryService queryService;

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ExchangeQuoteIntegrationTest.MutableClock clock;

    /** 정상일 때: 수집 후 조회가 캐시에서 나온다(hit). 이후 장애 테스트의 기준 상태를 만든다. */
    @Test
    @Order(1)
    void readsFromCacheWhileRedisIsUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(Instant.parse("2026-11-06T02:00:00Z"));
        collectOnce();
        double hitsBefore = lookups("hit");

        assertEquals(ALL.size(), queryService.findLatest(ALL).size());

        assertEquals(hitsBefore + ALL.size(), lookups("hit"));
    }

    /**
     * 응답 없음: 환율 조회는 명령 타임아웃(200ms)까지 기다린 뒤 DB 값으로 처리한다. 다시 답하면 캐시를 쓴다.
     * 견적은 Redis에만 저장하므로(#223) DB로 대신하지 못하고, 타임아웃 뒤 503으로 빠르게 실패한다.
     */
    @Test
    @Order(2)
    void fallsBackToDbAfterCommandTimeoutWhenRedisHangs() throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            double errorsBefore = lookups("error");
            long started = System.nanoTime();

            List<ExchangeRate> latest = queryService.findLatest(ALL);

            Duration took = Duration.ofNanos(System.nanoTime() - started);
            System.out.println("[측정] Redis 응답 없음일 때 환율 조회 시간: " + took.toMillis() + "ms");
            assertSameAsDb(latest);
            assertTrue(took.compareTo(Duration.ofMillis(200)) >= 0, "타임아웃까지 기다림: " + took);
            assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0, "타임아웃 뒤 바로 DB로: " + took);
            assertEquals(errorsBefore + ALL.size(), lookups("error"));

            // 견적 발급은 환율을 DB에서 읽어도 견적을 저장할 곳이 없어 503이다. 오래 매달리지 않는다
            long quoteStarted = System.nanoTime();
            issueQuote().andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_STORE_UNAVAILABLE"));
            Duration quoteTook = Duration.ofNanos(System.nanoTime() - quoteStarted);
            assertTrue(quoteTook.compareTo(Duration.ofSeconds(2)) < 0, "타임아웃 뒤 바로 실패: " + quoteTook);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        double hitsBefore = lookups("hit");
        queryService.findLatest(ALL);
        assertEquals(hitsBefore + ALL.size(), lookups("hit"), "Redis가 다시 답하면 캐시를 씀");
    }

    /**
     * 꺼짐: 환율 조회·수집은 DB로 동작한다. 연결이 끊긴 뒤에는 명령을 바로 거절해(REJECT_COMMANDS) 기다리지 않는다.
     * 견적 발급은 503이고, 앱 상태 확인(health)은 그대로 정상이다(Redis를 health에서 빼 둠).
     * 컨테이너를 멈추므로 마지막에 실행한다.
     */
    @Test
    @Order(3)
    void keepsWorkingWithDbWhenRedisIsDown() throws Exception {
        REDIS.stop();
        queryService.findLatest(ALL); // 연결이 끊긴 것을 Lettuce가 알아채게 한 번 부른다

        long started = System.nanoTime();
        List<ExchangeRate> latest = queryService.findLatest(ALL);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        System.out.println("[측정] Redis 꺼짐일 때 환율 조회 시간: " + took.toMillis() + "ms");
        assertSameAsDb(latest);
        assertTrue(took.compareTo(Duration.ofMillis(200)) < 0, "타임아웃을 기다리지 않음: " + took);

        // 수집은 DB 저장까지 성공하고, 조회는 새로 저장된 행을 DB에서 읽는다
        UUID before = repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow().getId();
        collectOnce();
        UUID after = repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow().getId();
        assertTrue(!before.equals(after), "Redis가 꺼져도 수집은 DB에 저장");
        assertEquals(after, queryService.findLatest(ForeignCurrency.JPY).orElseThrow().getId());

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(get("/api/v1/exchange-rates")).andExpect(status().isOk());
        issueQuote().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_STORE_UNAVAILABLE"));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private ResultActions issueQuote() throws Exception {
        return MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER).header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """));
    }

    private void assertSameAsDb(List<ExchangeRate> latest) {
        assertEquals(ALL.size(), latest.size());
        for (ExchangeRate rate : latest) {
            UUID inDb = repository.findLatest(RateSource.MOCK, rate.getCurrency()).orElseThrow().getId();
            assertEquals(inDb, rate.getId(), rate.getCurrency().name());
        }
    }

    private void collectOnce() {
        clock.set(clock.instant().plusSeconds(5));
        collector.collectIfNeeded();
    }

    private double lookups(String result) {
        return meterRegistry.get("exchange.rate.cache.lookups").tag("result", result).counter().count();
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

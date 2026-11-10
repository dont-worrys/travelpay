package com.travelpay.exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.application.rate.ExchangeRateStore;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 환율 조회가 DB를 몇 번 오가는지 실제 Postgres의 pg_stat_statements로 셉니다.
 * 여러 통화 조회는 쿼리 1번이고 BEGIN·COMMIT이 없어야 합니다(트랜잭션 없이 자동 커밋).
 * 같은 방법으로 트랜잭션이 있는 경로를 세어, 이 검사가 BEGIN을 실제로 잡는지도 확인합니다.
 * 캐시는 테스트 기본 설정으로 꺼져 있어(항상 미스) DB 경로만 셉니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.rate.collection.enabled=false",
        "exchange.order.recovery.enabled=false"
})
class ExchangeRateQueryRoundTripIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA)
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements",
                    "-c", "pg_stat_statements.track_utility=on");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ExchangeRateQueryService queryService;

    @Autowired
    private ExchangeRateStore store;

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA public");
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        for (ForeignCurrency currency : ForeignCurrency.values()) {
            repository.save(ExchangeRate.collected(new CollectedRate(currency, currency == ForeignCurrency.JPY ? 100 : 1,
                    new BigDecimal("900"), new BigDecimal("909"), new BigDecimal("891"), DATE, RateSource.KOREAEXIM),
                    Instant.parse("2026-10-28T02:10:00Z")));
        }
    }

    @Test
    void readsAllCurrenciesWithOneQueryAndNoTransaction() {
        resetStatements();

        List<ExchangeRate> latest = queryService.findLatest(List.of(ForeignCurrency.values()));

        assertEquals(ForeignCurrency.values().length, latest.size());
        assertEquals(1, calls("%cross join lateral%"), "여러 통화 조회는 쿼리 1번");
        assertEquals(0, calls("select%exchange_rates%limit%") - calls("%cross join lateral%"),
                "통화별 조회(findFirstBy…)를 따로 보내지 않음");
        assertEquals(0, calls("begin%"), "트랜잭션 없음 (BEGIN 0번)");
        assertEquals(0, calls("commit%"), "트랜잭션 없음 (COMMIT 0번)");
    }

    /** 한 통화 조회도 트랜잭션 없이 쿼리 1번입니다. 트랜잭션이 있으면 캐시에서 읽을 때도 DB 연결과 BEGIN을 씁니다. */
    @Test
    void readsOneCurrencyWithoutTransaction() {
        resetStatements();

        queryService.findLatest(ForeignCurrency.JPY);

        assertEquals(0, calls("begin%"), "트랜잭션 없음 (BEGIN 0번)");
    }

    /** 이 검사가 BEGIN을 실제로 셀 수 있는지: 읽기 전용 트랜잭션이 있는 수집 판단 조회는 BEGIN이 잡혀야 합니다. */
    @Test
    void detectsTransactionOnPathThatHasOne() {
        resetStatements();

        store.latestRateDate(RateSource.KOREAEXIM);

        assertTrue(calls("begin%") >= 1, "읽기 전용 트랜잭션이 있는 경로는 BEGIN이 보여야 함");
    }

    private void resetStatements() {
        jdbcTemplate.execute("SELECT public.pg_stat_statements_reset()");
    }

    /** 이 검사 쿼리 자신(pg_stat_statements 조회)은 세지 않습니다. */
    private long calls(String pattern) {
        Long calls = jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(calls), 0) FROM public.pg_stat_statements
                WHERE query ILIKE ? AND query NOT ILIKE '%pg_stat_statements%'
                """, Long.class, pattern);
        return calls == null ? 0 : calls;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** 외부 API 대신 빈 목록을 돌려줍니다. */
        @Bean
        @Primary
        ExchangeRateProvider testRateProvider() {
            return new ExchangeRateProvider() {
                @Override
                public RateSource source() {
                    return RateSource.KOREAEXIM;
                }

                @Override
                public List<CollectedRate> fetch(LocalDate date) {
                    return List.of();
                }
            };
        }
    }
}

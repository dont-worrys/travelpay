package com.travelpay.exchange.infrastructure.persistence.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres에서 여러 통화의 최신 환율을 쿼리 하나(LATERAL)로 읽는 저장소 메서드를 검증합니다.
 * 통화마다 기존 findLatest와 같은 결과인지, 출처를 섞지 않는지, 요청 순서를 지키는지 봅니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.enabled=false"
})
class ExchangeRateLatestQueryIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final LocalDate DAY_1 = LocalDate.of(2026, 10, 27);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 10, 28);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
    }

    /** 고시일이 더 최근인 것이 먼저이고, 같은 고시일이면 수집 시각이 더 최근인 것입니다 (기존 findLatest와 같은 규칙). */
    @Test
    void picksLatestRateDateThenLatestCollectionForEachCurrency() {
        save(RateSource.MOCK, ForeignCurrency.JPY, DAY_2, "2026-10-28T00:00:00Z", "900");
        UUID latestJpy = save(RateSource.MOCK, ForeignCurrency.JPY, DAY_2, "2026-10-28T00:00:05Z", "901");
        save(RateSource.MOCK, ForeignCurrency.JPY, DAY_1, "2026-10-28T00:00:10Z", "800"); // 수집은 늦지만 고시일이 이전
        UUID latestCny = save(RateSource.MOCK, ForeignCurrency.CNY, DAY_2, "2026-10-28T00:00:05Z", "190");
        save(RateSource.MOCK, ForeignCurrency.CNY, DAY_2, "2026-10-28T00:00:00Z", "189");

        List<ExchangeRate> latest = repository.findLatestOfEach(RateSource.MOCK, List.of(ForeignCurrency.JPY, ForeignCurrency.CNY));

        assertEquals(List.of(latestJpy, latestCny), ids(latest));
        assertEquals(List.of(latestJpy, latestCny), List.of(
                repository.findLatest(RateSource.MOCK, ForeignCurrency.JPY).orElseThrow().getId(),
                repository.findLatest(RateSource.MOCK, ForeignCurrency.CNY).orElseThrow().getId()));
    }

    @Test
    void keepsRequestedOrder() {
        UUID jpy = save(RateSource.MOCK, ForeignCurrency.JPY, DAY_2, "2026-10-28T00:00:00Z", "900");
        UUID cny = save(RateSource.MOCK, ForeignCurrency.CNY, DAY_2, "2026-10-28T00:00:00Z", "190");

        assertEquals(List.of(cny, jpy),
                ids(repository.findLatestOfEach(RateSource.MOCK, List.of(ForeignCurrency.CNY, ForeignCurrency.JPY))));
    }

    /** 아직 수집된 환율이 없는 통화는 빠지고, 다른 출처의 환율은 섞이지 않습니다. */
    @Test
    void skipsCurrencyWithoutRateAndDoesNotMixSources() {
        UUID mockJpy = save(RateSource.MOCK, ForeignCurrency.JPY, DAY_2, "2026-10-28T00:00:00Z", "900");
        save(RateSource.KOREAEXIM, ForeignCurrency.CNY, DAY_2, "2026-10-28T02:10:00Z", "190");

        assertEquals(List.of(mockJpy),
                ids(repository.findLatestOfEach(RateSource.MOCK, List.of(ForeignCurrency.JPY, ForeignCurrency.CNY))));
    }

    /**
     * 이력이 많을 때 통화마다 인덱스 맨 앞 한 줄만 읽는지 실행 계획으로 확인합니다.
     * 통화를 CHAR(3)로 맞추지 않으면 컬럼이 text로 변환되어 Seq Scan + Sort가 됩니다(실제로 겪은 문제).
     */
    @Test
    void readsOnlyFirstIndexRowOfEachCurrency() {
        jdbcTemplate.execute("""
                INSERT INTO exchange_rates (exchange_rate_id, target_currency, unit, mid_rate, buy_rate, sell_rate,
                                            source, rate_date, collected_at)
                SELECT gen_random_uuid(), c.currency, c.unit, 900, 909, 891, 'MOCK', DATE '2026-10-28',
                       TIMESTAMPTZ '2026-10-28 00:00:00+00' + make_interval(secs => 5 * i)
                FROM (VALUES ('JPY', 100), ('CNY', 1)) AS c(currency, unit)
                CROSS JOIN generate_series(1, 5000) AS i
                """);
        jdbcTemplate.execute("ANALYZE exchange_rates");

        String plan = String.join("\n", jdbcTemplate.queryForList("EXPLAIN (ANALYZE, COSTS OFF) "
                + ExchangeRateJpaRepository.LATEST_OF_EACH_SQL
                        .replace(":currencies", "'JPY,CNY'")
                        .replace(":source", "'MOCK'"), String.class));

        assertTrue(plan.contains("Index Scan using ix_exchange_rates_latest"), plan);
        assertFalse(plan.contains("Seq Scan"), plan);
        assertFalse(plan.contains("Sort"), plan);
        assertEquals(2, ids(repository.findLatestOfEach(RateSource.MOCK,
                List.of(ForeignCurrency.JPY, ForeignCurrency.CNY))).size());
    }

    /**
     * 전일 대비 기준(#272): 앞선 고시일 중 가장 최근 날의 마지막 수집분입니다.
     * 공식 환율은 주말을 건너뛰어 직전 영업일 값을 고르고, 같은 날·다른 출처는 섞지 않습니다.
     */
    @Test
    void findsLastRateOfMostRecentEarlierRateDate() {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        LocalDate monday = LocalDate.of(2026, 10, 26);
        save(RateSource.KOREAEXIM, ForeignCurrency.JPY, LocalDate.of(2026, 10, 22), "2026-10-22T02:10:00Z", "830");
        UUID fridayJpy = save(RateSource.KOREAEXIM, ForeignCurrency.JPY, friday, "2026-10-23T02:10:00Z", "835");
        save(RateSource.KOREAEXIM, ForeignCurrency.JPY, monday, "2026-10-26T02:10:00Z", "838.3");
        save(RateSource.MOCK, ForeignCurrency.JPY, LocalDate.of(2026, 10, 25), "2026-10-25T23:59:55Z", "999");

        assertEquals(fridayJpy, repository.findLatestBefore(RateSource.KOREAEXIM, ForeignCurrency.JPY, monday)
                .orElseThrow().getId());
    }

    /** mock은 하루에 여러 번 만들므로, 전날 것 중 가장 늦게 수집한 값을 고릅니다. 오늘 수집분은 보지 않습니다. */
    @Test
    void findsLatestCollectionOfPreviousDayForMock() {
        save(RateSource.MOCK, ForeignCurrency.JPY, DAY_1, "2026-10-27T23:59:50Z", "900");
        UUID lastOfDay1 = save(RateSource.MOCK, ForeignCurrency.JPY, DAY_1, "2026-10-27T23:59:55Z", "901");
        save(RateSource.MOCK, ForeignCurrency.JPY, DAY_2, "2026-10-28T00:00:00Z", "902");

        assertEquals(lastOfDay1, repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2)
                .orElseThrow().getId());
    }

    /** 앞선 고시일이 없으면(수집을 시작한 첫날) empty입니다. */
    @Test
    void findLatestBeforeIsEmptyWithoutEarlierRateDate() {
        save(RateSource.MOCK, ForeignCurrency.JPY, DAY_2, "2026-10-28T00:00:00Z", "900");

        assertTrue(repository.findLatestBefore(RateSource.MOCK, ForeignCurrency.JPY, DAY_2).isEmpty());
    }

    @Test
    void emptyRequestReturnsEmpty() {
        save(RateSource.MOCK, ForeignCurrency.JPY, DAY_2, "2026-10-28T00:00:00Z", "900");

        assertEquals(List.of(), repository.findLatestOfEach(RateSource.MOCK, List.of()));
    }

    private UUID save(RateSource source, ForeignCurrency currency, LocalDate rateDate, String collectedAt,
                      String midRate) {
        BigDecimal mid = new BigDecimal(midRate);
        return repository.save(ExchangeRate.collected(
                new CollectedRate(currency, currency == ForeignCurrency.JPY ? 100 : 1, mid, mid, mid, rateDate, source),
                Instant.parse(collectedAt))).getId();
    }

    private static List<UUID> ids(List<ExchangeRate> rates) {
        return rates.stream().map(ExchangeRate::getId).toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** 외부 API 대신 빈 목록을 돌려줍니다. 기동 시 수집이 아무것도 저장하지 않게 합니다. */
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

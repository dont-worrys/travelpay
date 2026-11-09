package com.travelpay.exchange;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.travelpay.exchange.application.rate.ExchangeRateCollector;
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

/**
 * 수출입은행 모드에서 한 통화만 수집된 날, 빠진 통화를 다음 주기에 다시 가져오는지 실제 Postgres로 확인합니다.
 * 공급자(KoreaEximRateProvider)가 값이 이상한 통화만 건너뛰어도 안전한지 보는 테스트입니다.
 * 수집 정책·저장소·최신 고시일 계산은 실제 빈을 쓰고, 공급자 응답만 바꿔 끼웁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.mode=koreaexim",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.rate.collection.enabled=false",
        "exchange.order.recovery.enabled=false"
})
class ExchangeRatePartialCollectionIntegrationTest {

    private static final String SCHEMA = "exchange_service";

    /** 공급자가 돌려줄 통화 목록입니다. 테스트마다 바꿉니다. */
    private static final AtomicReference<List<ForeignCurrency>> PUBLISHED = new AtomicReference<>(List.of());
    private static final AtomicInteger FETCH_COUNT = new AtomicInteger();

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
    private ExchangeRateCollector collector;

    @Autowired
    private ExchangeRateStore store;

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        today = LocalDate.now(clock);
        // 어제 고시분은 두 통화 모두 저장돼 있다
        for (ForeignCurrency currency : ForeignCurrency.values()) {
            repository.save(ExchangeRate.collected(rate(currency, today.minusDays(1)), Instant.now(clock)));
        }
        FETCH_COUNT.set(0);
    }

    /**
     * 오늘 첫 수집에서 CNY가 빠지면 JPY만 저장되고, 최신 고시일은 CNY 기준(어제)으로 남아 다음 주기에 다시 수집한다.
     * 다시 수집할 때 이미 저장된 JPY는 건너뛰고 CNY만 채운다. 그 뒤로는 더 수집하지 않는다.
     */
    @Test
    void collectsSkippedCurrencyOnNextCycleWithoutDuplicatingSavedOnes() {
        PUBLISHED.set(List.of(ForeignCurrency.JPY));
        collector.collectIfNeeded();

        assertEquals(today, latestDate(ForeignCurrency.JPY));
        assertEquals(today.minusDays(1), latestDate(ForeignCurrency.CNY));
        assertEquals(today.minusDays(1), store.latestRateDate(RateSource.KOREAEXIM).orElseThrow());

        PUBLISHED.set(List.of(ForeignCurrency.JPY, ForeignCurrency.CNY));
        collector.collectIfNeeded();

        assertEquals(2, FETCH_COUNT.get());
        assertEquals(today, latestDate(ForeignCurrency.CNY));
        assertEquals(1, countOn(ForeignCurrency.JPY, today));
        assertEquals(1, countOn(ForeignCurrency.CNY, today));

        collector.collectIfNeeded();
        assertEquals(2, FETCH_COUNT.get());
    }

    private LocalDate latestDate(ForeignCurrency currency) {
        return repository.findLatest(RateSource.KOREAEXIM, currency).orElseThrow().getRateDate();
    }

    private int countOn(ForeignCurrency currency, LocalDate date) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_rates"
                + " WHERE target_currency = ? AND rate_date = ?", Integer.class, currency.name(), date);
    }

    private static CollectedRate rate(ForeignCurrency currency, LocalDate date) {
        return new CollectedRate(currency, currency == ForeignCurrency.JPY ? 100 : 1,
                new BigDecimal("900"), new BigDecimal("909"), new BigDecimal("891"), date, RateSource.KOREAEXIM);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** 외부 API 대신, PUBLISHED에 담긴 통화만 고시된 것처럼 돌려줍니다. */
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
                    FETCH_COUNT.incrementAndGet();
                    return PUBLISHED.get().stream().map(currency -> rate(currency, date)).toList();
                }
            };
        }
    }
}

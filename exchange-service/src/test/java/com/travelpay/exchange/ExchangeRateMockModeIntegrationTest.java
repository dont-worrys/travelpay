package com.travelpay.exchange;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPosting;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * mock 모드(exchange.rate.mode=mock)로 띄운 앱에서 가짜 환율 수집·조회·견적·신청을 실제 Postgres로 검증합니다.
 * 가짜 환율은 진짜 난수로 움직이므로 정확한 값 대신 범위와 관계(같은 값인지, 범위 안인지)를 확인합니다.
 * 스케줄러는 끄고 수집기를 직접 불러, 시계를 5초씩 옮기며 수집합니다. 지갑·원장은 고정 응답으로 바꿉니다.
 * Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.mode=mock",
        "exchange.rate.collection.enabled=false",
        "exchange.order.recovery.fixed-delay=1h"
})
class ExchangeRateMockModeIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final BigDecimal OFFICIAL_JPY = new BigDecimal("838.30");
    /** 838.30의 ±2%를 안쪽으로 반올림한 범위 */
    private static final BigDecimal JPY_LOWER = new BigDecimal("821.54");
    private static final BigDecimal JPY_UPPER = new BigDecimal("855.06");

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
    private WebApplicationContext context;

    @Autowired
    private ExchangeRateRepository rateRepository;

    @Autowired
    private ExchangeRateCollector collector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeQuoteIntegrationTest.MutableClock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
        // koreaexim 모드로 전날 받아 둔 공식 환율. 가짜 환율의 기준값이 된다
        official(ForeignCurrency.JPY, OFFICIAL_JPY);
        official(ForeignCurrency.CNY, new BigDecimal("190.00"));
    }

    /** 수집할 때마다 통화별 MOCK 행이 쌓이고, 값은 공식 환율 ±2% 안에서 움직인다. 공식 환율 행은 그대로다 */
    @Test
    void eachCollectionAddsMockRatesAroundOfficialRate() {
        collectTimes(10);

        assertEquals(20, count(RateSource.MOCK));
        assertEquals(2, count(RateSource.KOREAEXIM));
        List<BigDecimal> jpy = jdbcTemplate.queryForList("SELECT mid_rate FROM " + SCHEMA
                + ".exchange_rates WHERE source = 'MOCK' AND target_currency = 'JPY'", BigDecimal.class);
        assertEquals(10, jpy.size());
        for (BigDecimal mid : jpy) {
            assertTrue(mid.compareTo(JPY_LOWER) >= 0 && mid.compareTo(JPY_UPPER) <= 0, "범위 밖: " + mid);
        }
    }

    /** 공식 환율 행이 있어도 mock 모드의 조회 API는 최신 MOCK 환율만 보여준다 */
    @Test
    void rateApiShowsLatestMockRateOnly() throws Exception {
        collectTimes(3);
        BigDecimal latestMock = latestMid(RateSource.MOCK, ForeignCurrency.JPY);

        String body = mockMvc.perform(get("/api/v1/exchange-rates").param("currency", "JPY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("MOCK"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(0, latestMock.compareTo(new BigDecimal(JsonPath.<String>read(body, "$.data[0].midRate"))));
    }

    /** 견적을 받은 뒤 환율이 계속 바뀌어도, 그 견적으로 신청한 주문은 견적 때의 환율 행과 값을 쓴다 */
    @Test
    void orderKeepsQuotedRateWhileMockRateMoves() throws Exception {
        collectTimes(1);
        String quoteBody = issueQuote().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String quoteId = JsonPath.read(quoteBody, "$.data.quoteId");
        BigDecimal quotedRate = new BigDecimal(JsonPath.<String>read(quoteBody, "$.data.appliedRate"));
        UUID quotedRateId = latestId(RateSource.MOCK, ForeignCurrency.JPY);

        // 견적 유효 시간(5분) 안에서 100초 동안 20번 더 수집
        collectTimes(20);
        assertNotEquals(quotedRateId, latestId(RateSource.MOCK, ForeignCurrency.JPY));

        mockMvc.perform(post("/api/v1/exchanges")
                        .header("User-Id", OWNER).header("User-Role", "USER").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quoteId":"%s","walletId":"%s"}
                                """.formatted(quoteId, WALLET_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        Map<String, Object> order = jdbcTemplate.queryForMap("SELECT rate_id, applied_rate FROM " + SCHEMA
                + ".exchange_orders");
        assertEquals(quotedRateId, order.get("rate_id"));
        assertEquals(0, quotedRate.compareTo((BigDecimal) order.get("applied_rate")));
    }

    /** 마지막 수집 후 30초까지는 견적을 내고, 그보다 오래되면 막는다 (수집이 멈췄을 때 오래된 환율로 견적이 나가지 않게) */
    @Test
    void quoteIsBlockedWhenMockRateIsStale() throws Exception {
        collectTimes(1);
        Instant collectedAt = clock.instant();

        clock.set(collectedAt.plusSeconds(30));
        issueQuote().andExpect(status().isCreated());

        clock.set(collectedAt.plusSeconds(31));
        issueQuote()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_RATE_UNAVAILABLE"));
    }

    /** 시계를 5초씩 옮기며 수집기를 n번 부른다. 마지막 수집 시각이 현재 시각으로 남는다 */
    private void collectTimes(int n) {
        for (int i = 0; i < n; i++) {
            clock.set(clock.instant().plusSeconds(5));
            collector.collectIfNeeded();
        }
    }

    private ResultActions issueQuote() throws Exception {
        return mockMvc.perform(post("/api/v1/exchange-quotes")
                .header("User-Id", OWNER).header("User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                        """));
    }

    private void official(ForeignCurrency currency, BigDecimal mid) {
        rateRepository.save(ExchangeRate.collected(new CollectedRate(currency, currency.quoteUnit(), mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                LocalDate.of(2026, 11, 1), RateSource.KOREAEXIM), NOW.minus(Duration.ofDays(1))));
    }

    private int count(RateSource source) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_rates WHERE source = ?",
                Integer.class, source.name());
    }

    private BigDecimal latestMid(RateSource source, ForeignCurrency currency) {
        return rateRepository.findLatest(source, currency).orElseThrow().getMidRate();
    }

    private UUID latestId(RateSource source, ForeignCurrency currency) {
        return rateRepository.findLatest(source, currency).orElseThrow().getId();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        ExchangeQuoteIntegrationTest.MutableClock testClock() {
            return new ExchangeQuoteIntegrationTest.MutableClock();
        }

        /** 누가 조회하든 WALLET_ID인 ACTIVE 지갑(원화·엔화 계정)을 돌려줍니다. */
        @Bean
        @Primary
        WalletBalanceReader testWalletBalanceReader() {
            return userId -> Optional.of(new WalletBalances(WALLET_ID, true, Map.of(
                    Currency.KRW, new WalletBalances.Account(new Money(Currency.KRW, 500_000), true),
                    Currency.JPY, new WalletBalances.Account(new Money(Currency.JPY, 0), true))));
        }

        /** 원장은 항상 기록 성공으로 답합니다. */
        @Bean
        @Primary
        LedgerClient testLedgerClient() {
            return new LedgerClient() {
                @Override
                public LedgerPostResult post(LedgerPosting posting) {
                    return new LedgerPostResult.Posted(UUID.randomUUID());
                }

                @Override
                public LedgerLookupResult findByOrderId(UUID orderId) {
                    return new LedgerLookupResult.NotFound();
                }
            };
        }
    }
}

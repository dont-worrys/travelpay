package com.travelpay.exchange;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Postgres에 Flyway 마이그레이션을 적용하고 ddl-auto: validate로 띄운 앱에서 견적 API를 검증합니다.
 * 외부 환율 API는 호출하지 않도록 빈 목록을 돌려주는 Provider로 바꾸고, 환율은 테스트마다 직접 저장합니다.
 * Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.enabled=false"
})
class ExchangeQuoteIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    private static final LocalDate RATE_DATE = LocalDate.of(2026, 10, 28);
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    /** 운영과 같이 접속 URL의 currentSchema로 스키마를 정합니다. Flyway가 스키마를 만듭니다. */
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        // 주문이 환율을 FK로 참조하므로 참조하는 테이블까지 함께 비운다 (CASCADE 대신 대상을 명시)
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
    }

    @Test
    void appliesFlywayMigrationsAndStartsWithSchemaValidation() {
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        // version이 없는 행은 Flyway가 스키마를 만들었다는 기록입니다.
        assertEquals(List.of("1", "2", "3", "4"), jdbcTemplate.queryForList(
                "SELECT version FROM " + SCHEMA + ".flyway_schema_history"
                        + " WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class));
    }

    @Test
    void issuesQuoteAndStoresMinorUnitsWithCalculationSnapshot() throws Exception {
        UUID rateId = saveRate(ForeignCurrency.JPY, "838.30", RATE_DATE);

        String body = issue(OWNER, "KRW", "JPY", "100000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fromAmount").value("100000"))
                .andExpect(jsonPath("$.data.toAmount").value("11928"))
                .andExpect(jsonPath("$.data.feeAmount").value("1000"))
                .andExpect(jsonPath("$.data.totalDebitAmount").value("101000"))
                .andExpect(jsonPath("$.data.appliedRate").value("838.3"))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = quoteRow(JsonPath.read(body, "$.data.quoteId"));
        assertEquals("USER", row.get("quote_type"));
        assertEquals(rateId, row.get("rate_id"));
        assertEquals(OWNER, row.get("user_id"));
        assertEquals("KRW", row.get("from_currency"));
        assertEquals("JPY", row.get("to_currency"));
        assertEquals(100000L, row.get("from_amount"));
        assertEquals(11928L, row.get("to_amount"));
        assertEquals(1000L, row.get("fee_amount"));
        assertEquals(101000L, row.get("total_debit_amount"));
        assertEquals(new BigDecimal("838.30000000"), row.get("applied_rate"));
        assertEquals(100, row.get("rate_unit"));
        assertEquals(RATE_DATE, ((java.sql.Date) row.get("rate_date")).toLocalDate());
        assertEquals(new BigDecimal("0.010000"), row.get("fee_rate"));
        assertEquals("ISSUED", row.get("status"));
        assertEquals(NOW.plus(Duration.ofMinutes(5)), instant(row, "expires_at"));
        assertEquals(NOW, instant(row, "created_at"));
        assertEquals(NOW, instant(row, "updated_at"));
    }

    @Test
    void storesYuanCentsAsMinorUnits() throws Exception {
        saveRate(ForeignCurrency.CNY, "198.69", RATE_DATE);

        String body = issue(OWNER, "CNY", "KRW", "100.50")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fromAmount").value("100.50"))
                .andExpect(jsonPath("$.data.toAmount").value("19968"))
                .andExpect(jsonPath("$.data.feeAmount").value("1.01"))
                .andExpect(jsonPath("$.data.totalDebitAmount").value("101.51"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = quoteRow(JsonPath.read(body, "$.data.quoteId"));
        assertEquals(10050L, row.get("from_amount"));
        assertEquals(19968L, row.get("to_amount"));
        assertEquals(101L, row.get("fee_amount"));
        assertEquals(10151L, row.get("total_debit_amount"));
    }

    @Test
    void rejectsInvalidRequestsWithoutSavingQuote() throws Exception {
        saveRate(ForeignCurrency.JPY, "838.30", RATE_DATE);

        issue(OWNER, "KRW", "JPY", "999")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_INVALID_AMOUNT"));
        issue(OWNER, "JPY", "KRW", "100.5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_INVALID_AMOUNT"));
        issue(OWNER, "KRW", "KRW", "10000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_UNSUPPORTED_PAIR"));
        issue(OWNER, "USD", "KRW", "100")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_UNSUPPORTED_CURRENCY"));

        assertEquals(0, quoteCount());
    }

    @Test
    void rejectsWhenRateIsMissingOrStale() throws Exception {
        issue(OWNER, "KRW", "JPY", "100000")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_RATE_UNAVAILABLE"));

        // 서울 기준 오늘(10-29)에서 8일 전 고시분은 견적에 쓰지 않습니다.
        saveRate(ForeignCurrency.JPY, "838.30", LocalDate.of(2026, 10, 21));
        issue(OWNER, "KRW", "JPY", "100000")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_RATE_UNAVAILABLE"));

        assertEquals(0, quoteCount());
    }

    @Test
    void findsOnlyOwnQuote() throws Exception {
        saveRate(ForeignCurrency.JPY, "838.30", RATE_DATE);
        String quoteId = issuedQuoteId();

        mockMvc.perform(get("/api/v1/exchange-quotes/{id}", quoteId).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quoteId").value(quoteId))
                .andExpect(jsonPath("$.data.toAmount").value("11928"));
        mockMvc.perform(get("/api/v1/exchange-quotes/{id}", quoteId).header("User-Id", OTHER).header("User-Role", "USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_NOT_FOUND"));
    }

    @Test
    void showsExpiredAtExpiryWithoutChangingStoredStatus() throws Exception {
        saveRate(ForeignCurrency.JPY, "838.30", RATE_DATE);
        String quoteId = issuedQuoteId();

        clock.set(NOW.plus(Duration.ofMinutes(5)).minusMillis(1));
        mockMvc.perform(get("/api/v1/exchange-quotes/{id}", quoteId).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(jsonPath("$.data.status").value("ISSUED"));

        clock.set(NOW.plus(Duration.ofMinutes(5)));
        mockMvc.perform(get("/api/v1/exchange-quotes/{id}", quoteId).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"));

        assertEquals("ISSUED", quoteRow(quoteId).get("status"));
    }

    @Test
    void databaseRejectsQuoteWhoseTotalDebitDoesNotAddUp() {
        UUID rateId = saveRate(ForeignCurrency.JPY, "838.30", RATE_DATE);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + ".exchange_quotes VALUES (gen_random_uuid(), 'USER', ?, ?, 'KRW', 'JPY',"
                        + " 100000, 11928, 838.3, 100, ?, 0.01, 1000, 100999, 'ISSUED', now(), now(), now())",
                rateId, OWNER, RATE_DATE));
    }

    private UUID saveRate(ForeignCurrency currency, String midRate, LocalDate rateDate) {
        BigDecimal mid = new BigDecimal(midRate);
        CollectedRate collected = new CollectedRate(currency, currency.quoteUnit(), mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                rateDate, RateSource.KOREAEXIM);
        return rateRepository.save(ExchangeRate.collected(collected, NOW.minus(Duration.ofHours(1)))).getId();
    }

    private ResultActions issue(UUID userId, String from, String to, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/exchange-quotes")
                .header("User-Id", userId)
                .header("User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromCurrency": "%s", "toCurrency": "%s", "amount": "%s"}
                        """.formatted(from, to, amount)));
    }

    private String issuedQuoteId() throws Exception {
        String body = issue(OWNER, "KRW", "JPY", "100000")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.quoteId");
    }

    private Map<String, Object> quoteRow(String quoteId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM " + SCHEMA + ".exchange_quotes WHERE quote_id = ?", UUID.fromString(quoteId));
    }

    private int quoteCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_quotes", Integer.class);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    /** 테스트에서 시각을 옮길 수 있는 시계입니다. 서비스와 JPA Auditing이 같은 시계를 씁니다. */
    static final class MutableClock extends Clock {

        private volatile Instant instant = NOW;

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

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

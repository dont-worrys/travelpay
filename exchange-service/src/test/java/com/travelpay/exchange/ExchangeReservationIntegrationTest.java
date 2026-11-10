package com.travelpay.exchange;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Postgres에 Flyway를 적용한 앱에서 예약 생성·재요청·조회·목록·취소를 HTTP로 검증합니다(#240).
 * 지갑 서비스는 고정된 지갑을 돌려주는 Reader로 바꿉니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.fixed-delay=1h"
})
class ExchangeReservationIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        TestRedis.register(registry);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeQuoteIntegrationTest.MutableClock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
    }

    @Test
    void reservesAndReplaysSameKeyWithoutNewRow() throws Exception {
        String first = reservationId(reserve(OWNER, "key-1", "830.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.type").value("EXCHANGE"))
                .andExpect(jsonPath("$.data.targetRate").value("830"))
                .andExpect(jsonPath("$.data.rateUnit").value(100))
                .andExpect(jsonPath("$.data.condition").value("AT_OR_BELOW")));

        // 응답을 못 받은 앱이 같은 키·같은 내용으로 다시 보내면 처음 예약을 돌려준다
        String replayed = reservationId(reserve(OWNER, "key-1", "830.00").andExpect(status().isCreated()));
        assertEquals(first, replayed);
        assertEquals(1, reservationCount());

        // 같은 키로 다른 내용을 보내면 409
        reserve(OWNER, "key-1", "820.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_RESERVATION_IDEMPOTENCY_KEY_REUSED"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM " + SCHEMA + ".exchange_reservations WHERE reservation_id = ?", UUID.fromString(first));
        assertEquals("WAITING", row.get("status"));
        assertEquals(OWNER, row.get("user_id"));
    }

    @Test
    void showsAndListsOnlyOwnReservations() throws Exception {
        String mine = reservationId(reserve(OWNER, "key-1", "830.00"));
        clock.set(NOW.plusSeconds(1));
        String newer = reservationId(reserve(OWNER, "key-2", "825.00"));
        reserve(OTHER, "key-1", "830.00").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/exchange-reservations/{id}", mine).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.walletId").value(WALLET_ID.toString()))
                .andExpect(jsonPath("$.data.orderStatus").doesNotExist());
        mockMvc.perform(get("/api/v1/exchange-reservations/{id}", mine).header("User-Id", OTHER).header("User-Role", "USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_RESERVATION_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/exchange-reservations").header("User-Id", OWNER).header("User-Role", "USER")
                        .param("status", "WAITING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].reservationId").value(newer))
                .andExpect(jsonPath("$.data.items[1].reservationId").value(mine));
    }

    @Test
    void cancelsWaitingReservationOnlyOnce() throws Exception {
        String id = reservationId(reserve(OWNER, "key-1", "830.00"));

        cancel(OTHER, id).andExpect(status().isNotFound());
        cancel(OWNER, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
        cancel(OWNER, id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_RESERVATION_NOT_WAITING"));

        assertEquals("CANCELED", jdbcTemplate.queryForObject("SELECT status FROM " + SCHEMA
                + ".exchange_reservations WHERE reservation_id = ?", String.class, UUID.fromString(id)));
    }

    private ResultActions reserve(UUID userId, String idempotencyKey, String targetRate) throws Exception {
        return mockMvc.perform(post("/api/v1/exchange-reservations")
                .header("User-Id", userId)
                .header("User-Role", "USER")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"walletId":"%s","fromCurrency":"KRW","toCurrency":"JPY","amount":"100000",
                         "targetRate":"%s","expiresAt":"2026-11-17T03:00:00Z"}
                        """.formatted(WALLET_ID, targetRate)));
    }

    private ResultActions cancel(UUID userId, String reservationId) throws Exception {
        return mockMvc.perform(post("/api/v1/exchange-reservations/{id}/cancel", reservationId)
                .header("User-Id", userId)
                .header("User-Role", "USER"));
    }

    private static String reservationId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.reservationId");
    }

    private int reservationCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_reservations", Integer.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        ExchangeQuoteIntegrationTest.MutableClock testClock() {
            return new ExchangeQuoteIntegrationTest.MutableClock();
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

        /** 누가 조회하든 WALLET_ID인 ACTIVE 지갑(원화·엔화 계정)을 돌려줍니다. */
        @Bean
        @Primary
        WalletBalanceReader testWalletBalanceReader() {
            return userId -> Optional.of(new WalletBalances(WALLET_ID, true, Map.of(
                    Currency.KRW, new WalletBalances.Account(new Money(Currency.KRW, 500_000), true),
                    Currency.JPY, new WalletBalances.Account(new Money(Currency.JPY, 0), true))));
        }
    }
}

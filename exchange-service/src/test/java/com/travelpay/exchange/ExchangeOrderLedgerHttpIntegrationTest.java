package com.travelpay.exchange;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import org.junit.jupiter.api.AfterAll;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가짜 원장 대신 실제 원장 클라이언트(Feign + LedgerClientAdapter)로 환전 신청·복구를 검증합니다.
 * 원장 자리에는 테스트용 HTTP 서버를 띄워 응답을 바꿔 가며, Spring이 공통 ApiResponse로 응답을 읽는지와
 * 2xx 본문 해석 실패가 결과 모름(PROCESSING)으로 남았다가 복구에서 확정되는지 확인합니다.
 * 지갑 서비스는 고정된 지갑을 돌려주는 Reader로 바꿉니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.fixed-delay=1h"
})
class ExchangeOrderLedgerHttpIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TX_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String POSTED_BODY = """
            {"success":true,"data":{"transactionId":"%s","status":"POSTED"},"error":null}
            """.formatted(TX_ID);
    /** 성공 상태 코드인데 중간에 끊긴 본문. 원장은 이미 기록했을 수 있다 */
    private static final String BROKEN_BODY = "{\"success\":true,\"data\":{\"transactionId\":";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    private static final HttpServer LEDGER = startLedger();
    private static final AtomicInteger LEDGER_CALLS = new AtomicInteger();
    private static volatile String ledgerBody = POSTED_BODY;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.ledger.url", () -> "http://127.0.0.1:" + LEDGER.getAddress().getPort());
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ExchangeRateRepository rateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeQuoteIntegrationTest.MutableClock clock;

    @Autowired
    private ExchangeOrderRecoveryService recoveryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        LEDGER_CALLS.set(0);
        ledgerBody = POSTED_BODY;
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
        BigDecimal mid = new BigDecimal("838.30");
        rateRepository.save(ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100, mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                LocalDate.of(2026, 10, 28), RateSource.KOREAEXIM), NOW.minus(Duration.ofHours(1))));
    }

    @AfterAll
    static void stopLedger() {
        LEDGER.stop(0);
    }

    /** 실제 Feign 호출에서 원장의 공통 응답 형식을 읽어 바로 완료된다 */
    @Test
    void postedLedgerResponseIsReadThroughCommonApiResponse() throws Exception {
        place(issuedQuoteId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertEquals(1, LEDGER_CALLS.get());
        assertEquals(TX_ID, onlyOrder().get("ledger_transaction_id"));
    }

    /** 2xx인데 본문이 깨지면 사람 확인이 아니라 처리 중으로 두고, 복구가 같은 요청을 다시 보내 완료한다 */
    @Test
    void unreadableSuccessBodyStaysProcessingAndRecoveryCompletesIt() throws Exception {
        ledgerBody = BROKEN_BODY;
        place(issuedQuoteId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertEquals("PROCESSING", onlyOrder().get("status"));

        // 원장이 정상으로 답하고, 첫 확인 뒤 대기 시간(30초 × 2)이 지난 시점의 복구
        ledgerBody = POSTED_BODY;
        clock.set(NOW.plus(Duration.ofMinutes(2)));
        assertEquals(1, recoveryService.recover(10).settled());

        Map<String, Object> order = onlyOrder();
        assertEquals("COMPLETED", order.get("status"));
        assertEquals(TX_ID, order.get("ledger_transaction_id"));
        assertEquals(2, LEDGER_CALLS.get());
    }

    private Map<String, Object> onlyOrder() {
        return jdbcTemplate.queryForMap("SELECT * FROM " + SCHEMA + ".exchange_orders");
    }

    private String issuedQuoteId() throws Exception {
        String body = mockMvc.perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER)
                        .header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.quoteId");
    }

    private ResultActions place(String quoteId) throws Exception {
        return mockMvc.perform(post("/api/v1/exchanges")
                .header("User-Id", OWNER)
                .header("User-Role", "USER")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quoteId":"%s","walletId":"%s"}
                        """.formatted(quoteId, WALLET_ID)));
    }

    /** 원장 거래 등록 경로에 지금 정해 둔 본문을 201로 답하는 서버 */
    private static HttpServer startLedger() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/ledger/transactions", ExchangeOrderLedgerHttpIntegrationTest::respond);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("테스트 원장 서버를 띄우지 못했습니다.", e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        LEDGER_CALLS.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        byte[] body = ledgerBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
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

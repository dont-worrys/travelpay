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

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelpay.exchange.application.order.ExchangeOrderBacklogMonitor;
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
import io.micrometer.core.instrument.MeterRegistry;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 MANUAL_REVIEW 처리를 실제 원장 클라이언트(Feign + LedgerClientAdapter)와 실제 Postgres로 검증합니다.
 * 원장 자리에는 테스트용 HTTP 서버를 띄웁니다. 거래 등록(POST)은 400으로 답해 주문을 MANUAL_REVIEW로 만들고,
 * 단건 조회(GET)는 시나리오마다 POSTED·REJECTED·기록 없음·조회 실패로 답합니다.
 * POSTED 응답의 항목은 환전 서비스가 실제로 보낸 등록 요청을 받아, 원장처럼 USER 항목에 accountType WALLET을 채워 돌려줍니다.
 * 처리 뒤 고객이 같은 멱등 키로 다시 신청하면 확정된 결과를 받는지도 확인합니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.enabled=false"
})
class AdminExchangeManualReviewHttpIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID LEDGER_TX_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String IDEMPOTENCY_KEY = "key-1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    private static final HttpServer LEDGER = startLedger();
    /** 환전 서비스가 원장에 보낸 마지막 거래 등록 요청 본문 */
    private static volatile String registeredRequest;
    /** 원장이 받은 마지막 단건 조회의 쿼리 문자열 */
    private static volatile String lastLookupQuery;
    private static volatile LedgerReply lookupReply;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        TestRedis.register(registry);
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
    private ExchangeOrderBacklogMonitor backlogMonitor;

    @Autowired
    private MeterRegistry meterRegistry;

    private MockMvc mockMvc;
    private String quoteId;
    private UUID orderId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        registeredRequest = null;
        lastLookupQuery = null;
        lookupReply = null;
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
        BigDecimal mid = new BigDecimal("838.30");
        rateRepository.save(ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100, mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                LocalDate.of(2026, 11, 8), RateSource.KOREAEXIM), NOW.minus(Duration.ofHours(1))));

        // 원장 등록이 400(우리 요청 형식 오류)이라 주문은 MANUAL_REVIEW(LEDGER_BAD_REQUEST)가 된다
        quoteId = issuedQuoteId();
        String body = place().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        orderId = UUID.fromString(JsonPath.read(body, "$.data.orderId"));
        assertNotNull(registeredRequest, "환전 서비스가 원장에 등록 요청을 보냄");
    }

    @AfterAll
    static void stopLedger() {
        LEDGER.stop(0);
    }

    /** 원장에 같은 항목으로 반영돼 있으면 COMPLETED로 확정하고, 근거와 관리자를 이력에 남긴다 */
    @Test
    void postedWithSameEntriesCompletesOrderAndRecordsMasterHistory() throws Exception {
        lookupReply = LedgerReply.ok(postedBody(postedEntriesFromRegisteredRequest()));

        mockMvc.perform(asAdmin(get("/api/v1/admin/exchanges/{orderId}", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewReason").value("LEDGER_BAD_REQUEST"))
                .andExpect(jsonPath("$.data.ledgerLookupFailed").value(false))
                .andExpect(jsonPath("$.data.ledger.status").value("POSTED"))
                .andExpect(jsonPath("$.data.expected.resolvable").value(true))
                .andExpect(jsonPath("$.data.expected.toStatus").value("COMPLETED"));
        assertEquals("referenceType=EXCHANGE&referenceId=" + orderId, lastLookupQuery);

        resolve("원장 반영 확인")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.ledgerTransactionId").value(LEDGER_TX_ID.toString()));

        Map<String, Object> order = order();
        assertEquals("COMPLETED", order.get("status"));
        assertEquals(LEDGER_TX_ID, order.get("ledger_transaction_id"));
        assertNotNull(order.get("settled_at"));
        Map<String, Object> history = masterHistory();
        assertEquals("MANUAL_REVIEW", history.get("from_status"));
        assertEquals("COMPLETED", history.get("to_status"));
        assertEquals("LEDGER_POSTED", history.get("reason"));
        assertEquals(ADMIN, history.get("changed_by_id"));
        assertEquals(LEDGER_TX_ID, history.get("ledger_transaction_id"));
        assertEquals("원장 반영 확인", history.get("memo"));

        // 고객이 같은 키로 다시 신청하면 확정된 결과(완료)를 받는다
        place().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    /** 원장 항목이 주문과 다르면 처리하지 않고(409) MANUAL_REVIEW로 남기며, 단건 조회에서 두 항목을 함께 보여 준다 */
    @Test
    void postedWithDifferentEntriesIsNotResolvedAndShowsBothEntryLists() throws Exception {
        ArrayNode entries = postedEntriesFromRegisteredRequest();
        ((ObjectNode) entries.get(0)).put("amount", entries.get(0).get("amount").asLong() + 1);
        lookupReply = LedgerReply.ok(postedBody(entries));

        int entryCount = entries.size();
        mockMvc.perform(asAdmin(get("/api/v1/admin/exchanges/{orderId}", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expected.resolvable").value(false))
                .andExpect(jsonPath("$.data.expected.expectedEntries.length()").value(entryCount))
                .andExpect(jsonPath("$.data.expected.actualEntries.length()").value(entryCount));

        resolve(null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_LEDGER_CORRECTION_REQUIRED"));

        assertEquals("MANUAL_REVIEW", order().get("status"));
        assertEquals(0, masterHistoryCount());
    }

    /** 원장이 거절한 거래면 원장 거절 사유로 FAILED 확정한다 */
    @Test
    void rejectedLedgerTransactionFailsOrderWithLedgerReason() throws Exception {
        lookupReply = LedgerReply.ok("""
                {"success":true,"data":{"transactionId":"%s","status":"REJECTED","rejectReason":"INSUFFICIENT_BALANCE",
                 "referenceType":"EXCHANGE","referenceId":"%s","entries":[]},"error":null}
                """.formatted(LEDGER_TX_ID, orderId));

        resolve(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("INSUFFICIENT_BALANCE"));

        Map<String, Object> order = order();
        assertEquals("FAILED", order.get("status"));
        assertEquals("INSUFFICIENT_BALANCE", order.get("failure_reason"));
        assertNull(order.get("ledger_transaction_id"), "거절된 거래는 주문에 원장 거래 ID를 남기지 않음");
        assertEquals(LEDGER_TX_ID, masterHistory().get("ledger_transaction_id"), "근거는 이력에 남김");
    }

    /**
     * 원장 기록이 없으면(404 + LEDGER_TRANSACTION_NOT_FOUND) 다시 보내지 않고 NOT_PROCESSED로 취소한다.
     * 고객이 같은 키로 다시 신청하면 그 실패 사유(422)를 받는다.
     */
    @Test
    void notFoundCancelsOrderAndCustomerSeesNotProcessed() throws Exception {
        lookupReply = new LedgerReply(404, """
                {"success":false,"data":null,"error":{"code":"LEDGER_TRANSACTION_NOT_FOUND","message":"없음"}}
                """);

        resolve(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("NOT_PROCESSED"));

        assertEquals("NOT_PROCESSED", order().get("failure_reason"));
        Map<String, Object> history = masterHistory();
        assertEquals("LEDGER_NOT_FOUND", history.get("reason"));
        assertNull(history.get("ledger_transaction_id"));
        assertNull(history.get("memo"));

        place().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_NOT_PROCESSED"));
    }

    /** 코드 없는 404(경로 오류 등)는 기록 없음으로 단정하지 않는다. 처리는 503, 단건 조회는 조회 실패로 보여 준다 */
    @Test
    void notFoundWithoutLedgerCodeIsLookupFailureAndKeepsOrder() throws Exception {
        lookupReply = new LedgerReply(404, "{}");

        mockMvc.perform(asAdmin(get("/api/v1/admin/exchanges/{orderId}", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ledgerLookupFailed").value(true));
        resolve(null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_LEDGER_LOOKUP_UNAVAILABLE"));

        assertEquals("MANUAL_REVIEW", order().get("status"));
        assertEquals(0, masterHistoryCount());
    }

    /** 원장 장애(500)면 결론을 내지 않고 상태를 그대로 둔다 */
    @Test
    void ledgerServerErrorKeepsOrderInManualReview() throws Exception {
        lookupReply = new LedgerReply(500, """
                {"success":false,"data":null,"error":{"code":"COMMON_INTERNAL_ERROR","message":"오류"}}
                """);

        resolve(null).andExpect(status().isServiceUnavailable());

        assertEquals("MANUAL_REVIEW", order().get("status"));
        assertEquals(0, masterHistoryCount());
    }

    /** 이미 처리된 주문은 다시 처리하지 않는다 (같은 관리자의 재요청 포함) */
    @Test
    void resolvedOrderCannotBeResolvedAgain() throws Exception {
        lookupReply = new LedgerReply(404, """
                {"success":false,"data":null,"error":{"code":"LEDGER_TRANSACTION_NOT_FOUND","message":"없음"}}
                """);
        resolve(null).andExpect(status().isOk());

        resolve(null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_NOT_MANUAL_REVIEW"));
        assertEquals(1, masterHistoryCount());
    }

    /** 처리하면 처리 이력 목록에 관리자·근거·사람 확인 사유·실패 사유가 함께 나온다 */
    @Test
    void resolvedOrderAppearsInResolutionList() throws Exception {
        lookupReply = new LedgerReply(404, """
                {"success":false,"data":null,"error":{"code":"LEDGER_TRANSACTION_NOT_FOUND","message":"없음"}}
                """);
        resolve(null).andExpect(status().isOk());

        mockMvc.perform(asAdmin(get("/api/v1/admin/exchanges/resolutions").param("actorId", ADMIN.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.items[0].actorId").value(ADMIN.toString()))
                .andExpect(jsonPath("$.data.items[0].fromStatus").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data.items[0].toStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].resolutionReason").value("LEDGER_NOT_FOUND"))
                .andExpect(jsonPath("$.data.items[0].reviewReason").value("LEDGER_BAD_REQUEST"))
                .andExpect(jsonPath("$.data.items[0].failureReason").value("NOT_PROCESSED"));

        mockMvc.perform(asAdmin(get("/api/v1/admin/exchanges/resolutions").param("actorId", OWNER.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    /** 사람 확인 대기 지표는 모니터가 센 값을 보여 주고, 관리자가 처리하면 다음 갱신에서 줄어든다 */
    @Test
    void manualReviewGaugeFollowsMonitorRefresh() throws Exception {
        backlogMonitor.refresh();
        assertEquals(1.0, meterRegistry.get("exchange.order.manual_review").gauge().value());
        assertEquals(0.0, meterRegistry.get("exchange.order.processing.stuck").gauge().value());

        lookupReply = new LedgerReply(404, """
                {"success":false,"data":null,"error":{"code":"LEDGER_TRANSACTION_NOT_FOUND","message":"없음"}}
                """);
        resolve(null).andExpect(status().isOk());
        assertEquals(1.0, meterRegistry.get("exchange.order.manual_review").gauge().value(), "갱신 전에는 이전 값");

        backlogMonitor.refresh();
        assertEquals(0.0, meterRegistry.get("exchange.order.manual_review").gauge().value());
    }

    /** MASTER가 아니면 원장을 조회하기 전에 막는다 */
    @Test
    void nonMasterIsForbiddenBeforeLedgerLookup() throws Exception {
        mockMvc.perform(post("/api/v1/admin/exchanges/{orderId}/resolve", orderId)
                        .header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));

        assertNull(lastLookupQuery, "원장 조회 없음");
        assertEquals("MANUAL_REVIEW", order().get("status"));
    }

    private ResultActions resolve(String memo) throws Exception {
        var request = asAdmin(post("/api/v1/admin/exchanges/{orderId}/resolve", orderId));
        if (memo != null) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"memo\":\"%s\"}".formatted(memo));
        }
        return mockMvc.perform(request);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asAdmin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("User-Id", ADMIN).header("User-Role", "MASTER");
    }

    private Map<String, Object> order() {
        return jdbcTemplate.queryForMap("SELECT * FROM " + SCHEMA + ".exchange_orders WHERE order_id = ?", orderId);
    }

    private Map<String, Object> masterHistory() {
        return jdbcTemplate.queryForMap("SELECT * FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE order_id = ? AND changed_by_type = 'MASTER'", orderId);
    }

    private int masterHistoryCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE order_id = ? AND changed_by_type = 'MASTER'", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private String issuedQuoteId() throws Exception {
        String body = mockMvc.perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER).header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.quoteId");
    }

    private ResultActions place() throws Exception {
        return mockMvc.perform(post("/api/v1/exchanges")
                .header("User-Id", OWNER).header("User-Role", "USER")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quoteId":"%s","walletId":"%s"}
                        """.formatted(quoteId, WALLET_ID)));
    }

    /**
     * 환전 서비스가 보낸 등록 요청의 항목을 원장 단건 조회 응답 모양으로 바꿉니다.
     * 원장은 요청에서 비워 보낸 USER 항목의 accountType을 저장된 값(WALLET)으로 채워 보여 줍니다.
     */
    private static ArrayNode postedEntriesFromRegisteredRequest() {
        ArrayNode entries = (ArrayNode) JSON.readTree(registeredRequest).get("entries");
        for (JsonNode entry : entries) {
            if ("USER".equals(entry.get("ownerType").asString())) {
                ((ObjectNode) entry).put("accountType", "WALLET");
            }
        }
        return entries;
    }

    private String postedBody(ArrayNode entries) {
        return """
                {"success":true,"data":{"transactionId":"%s","status":"POSTED","rejectReason":null,
                 "referenceType":"EXCHANGE","referenceId":"%s","createdAt":"2026-11-09T03:00:00Z","entries":%s},"error":null}
                """.formatted(LEDGER_TX_ID, orderId, JSON.writeValueAsString(entries));
    }

    private record LedgerReply(int status, String body) {

        static LedgerReply ok(String body) {
            return new LedgerReply(200, body);
        }
    }

    /** 등록(POST)은 항상 400, 단건 조회(GET)는 지금 정해 둔 응답으로 답하는 원장 서버 */
    private static HttpServer startLedger() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/ledger/transactions", AdminExchangeManualReviewHttpIntegrationTest::respond);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("테스트 원장 서버를 띄우지 못했습니다.", e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LedgerReply reply;
        if ("POST".equals(exchange.getRequestMethod())) {
            registeredRequest = requestBody;
            reply = new LedgerReply(400, """
                    {"success":false,"data":null,"error":{"code":"COMMON_INVALID_REQUEST","message":"형식 오류"}}
                    """);
        } else {
            lastLookupQuery = exchange.getRequestURI().getRawQuery();
            reply = lookupReply != null ? lookupReply : new LedgerReply(500, "{}");
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length);
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

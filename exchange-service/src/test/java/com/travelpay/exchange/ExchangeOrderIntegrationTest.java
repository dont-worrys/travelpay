package com.travelpay.exchange;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.exchange.application.order.ExchangeOrderLedgerProcessor;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPosting;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Postgres에 Flyway를 적용한 앱에서 견적 발급부터 환전 신청·재요청·조회까지 검증합니다.
 * 지갑 서비스는 호출하지 않도록 고정된 지갑을 돌려주는 Reader로 바꿉니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.fixed-delay=1h"
})
class ExchangeOrderIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    private static final LocalDate RATE_DATE = LocalDate.of(2026, 10, 28);
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
    private ExchangeRateRepository rateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeQuoteIntegrationTest.MutableClock clock;

    @Autowired
    private FakeLedger ledger;

    @Autowired
    private ExchangeOrderRecoveryService recoveryService;

    @Autowired
    private ExchangeOrderLedgerProcessor ledgerProcessor;

    @Autowired
    private ExchangeOrderRepository orderRepository;

    @Autowired
    private StringRedisTemplate redis;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        ledger.reset();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
        BigDecimal mid = new BigDecimal("838.30");
        rateRepository.save(ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100, mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                RATE_DATE, RateSource.KOREAEXIM), NOW.minus(Duration.ofHours(1))));
    }

    @Test
    void placesOrderWithQuoteSnapshotAndCreationHistory() throws Exception {
        String quoteId = issuedQuoteId(OWNER);

        String orderId = JsonPath.read(place(OWNER, "key-1", quoteId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.type").value("EXCHANGE"))
                .andExpect(jsonPath("$.data.quoteId").value(quoteId))
                .andExpect(jsonPath("$.data.totalDebitAmount").value("101000"))
                .andExpect(jsonPath("$.data.toAmount").value("11928"))
                .andReturn().getResponse().getContentAsString(), "$.data.orderId");

        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT * FROM " + SCHEMA + ".exchange_orders WHERE order_id = ?", UUID.fromString(orderId));
        assertEquals("PROCESSING", order.get("status"));
        assertEquals(101000L, order.get("total_debit_amount"));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE order_id = ?", Integer.class, UUID.fromString(orderId)));
    }

    /**
     * 견적 조회 상태는 주문 테이블로 계산한다(#223): 주문에 쓴 견적은 USED이고, 만료 시각이 지나도 USED다.
     * 주문에 쓰지 않은 견적은 만료 전 ISSUED, 만료 후 EXPIRED다.
     */
    @Test
    void quoteUsedByOrderIsReportedAsUsedEvenAfterExpiry() throws Exception {
        String usedQuote = issuedQuoteId(OWNER);
        String unusedQuote = issuedQuoteId(OWNER);
        place(OWNER, "key-1", usedQuote).andExpect(status().isAccepted());

        quote(usedQuote).andExpect(jsonPath("$.data.status").value("USED"));
        quote(unusedQuote).andExpect(jsonPath("$.data.status").value("ISSUED"));

        clock.set(NOW.plus(Duration.ofMinutes(6)));
        quote(usedQuote).andExpect(jsonPath("$.data.status").value("USED"));
        quote(unusedQuote).andExpect(jsonPath("$.data.status").value("EXPIRED"));
    }

    private ResultActions quote(String quoteId) throws Exception {
        return mockMvc.perform(get("/api/v1/exchange-quotes/{id}", quoteId).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(status().isOk());
    }

    @Test
    void retryWithSameKeyReturnsSameOrderEvenAfterQuoteExpired() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        String first = orderId(place(OWNER, "key-1", quoteId).andExpect(status().isAccepted()));

        // 응답을 못 받은 앱이 견적 만료 뒤에 같은 키로 다시 보낸 경우. 견적이 아니라 기존 주문으로 답한다.
        clock.set(NOW.plus(Duration.ofMinutes(10)));
        String retried = orderId(place(OWNER, "key-1", quoteId).andExpect(status().isAccepted()));

        assertEquals(first, retried);
        assertEquals(1, orderCount());
    }

    /**
     * Redis 키 수명(유효 5분 + 5분)이 지나 견적이 사라진 뒤(#223):
     * 견적 조회와 그 견적으로 하는 새 신청은 404 EXCHANGE_QUOTE_NOT_FOUND이고,
     * 같은 멱등 키 재요청은 견적을 읽지 않고 주문을 먼저 찾으므로 저장된 주문으로 그대로 응답한다.
     * 키 수명이 끝나면 Redis가 키를 지우므로, 10분을 기다리는 대신 키를 지워 같은 상태를 만든다(수명 값 자체는 ExchangeQuoteIntegrationTest가 검증).
     */
    @Test
    void afterQuoteKeyIsGoneLookupAndNewOrderAre404ButRetryReturnsStoredOrder() throws Exception {
        String usedQuote = issuedQuoteId(OWNER);
        String first = orderId(place(OWNER, "key-1", usedQuote).andExpect(status().isAccepted()));
        String unusedQuote = issuedQuoteId(OWNER);

        clock.set(NOW.plus(Duration.ofMinutes(11)));
        redis.delete(List.of("exchange:quote:" + usedQuote, "exchange:quote:" + unusedQuote));

        for (String quoteId : List.of(usedQuote, unusedQuote)) {
            mockMvc.perform(get("/api/v1/exchange-quotes/{id}", quoteId).header("User-Id", OWNER).header("User-Role", "USER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_NOT_FOUND"));
        }
        place(OWNER, "key-2", unusedQuote)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_NOT_FOUND"));

        String retried = orderId(place(OWNER, "key-1", usedQuote).andExpect(status().isAccepted()));
        assertEquals(first, retried);
        assertEquals(1, orderCount());
    }

    /**
     * API 문서(#233): 사용자 그룹(exchange)과 관리자 그룹(exchange-admin)이 경로를 나눠 갖고,
     * 회원 헤더는 요청자를 쓰는 API에만, 환율 조회(공개)에는 붙지 않는다. 환전 신청의 Idempotency-Key는 필수로 보인다.
     */
    @Test
    void apiDocsSplitUserAndAdminGroupsAndDocumentHeadersWhereNeeded() throws Exception {
        String user = mockMvc.perform(get("/v3/api-docs/exchange")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String admin = mockMvc.perform(get("/v3/api-docs/exchange-admin")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> userPaths = JsonPath.read(user, "$.paths");
        Map<String, Object> adminPaths = JsonPath.read(admin, "$.paths");
        assertTrue(userPaths.keySet().containsAll(List.of("/api/v1/exchange-rates", "/api/v1/exchange-limits",
                "/api/v1/exchange-quotes", "/api/v1/exchanges", "/api/v1/exchanges/{orderId}")), userPaths.keySet().toString());
        assertTrue(userPaths.keySet().stream().noneMatch(path -> path.startsWith("/api/v1/admin")), userPaths.keySet().toString());
        assertTrue(adminPaths.keySet().containsAll(List.of("/api/v1/admin/exchanges/manual-reviews",
                "/api/v1/admin/exchanges/resolutions", "/api/v1/admin/exchanges/{orderId}/resolve")), adminPaths.keySet().toString());
        assertTrue(adminPaths.keySet().stream().allMatch(path -> path.startsWith("/api/v1/admin/exchanges")), adminPaths.keySet().toString());

        // 공개 API에는 회원 헤더가 없고, 요청자를 쓰는 API에는 필수로 있다
        assertEquals(List.of(), JsonPath.read(user, "$.paths['/api/v1/exchange-rates'].get.parameters[?(@.name == 'User-Id')]"));
        assertEquals(List.of(true), JsonPath.read(user, "$.paths['/api/v1/exchanges'].post.parameters[?(@.name == 'User-Id')].required"));
        assertEquals(List.of(true), JsonPath.read(user, "$.paths['/api/v1/exchanges'].post.parameters[?(@.name == 'Idempotency-Key')].required"));
        assertEquals(List.of("MASTER"), JsonPath.read(admin,
                "$.paths['/api/v1/admin/exchanges/resolutions'].get.parameters[?(@.name == 'User-Role')].example"));
    }

    @Test
    void sameQuoteWithAnotherKeyIsAlreadyUsed() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());

        place(OWNER, "key-2", quoteId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_ALREADY_USED"));
        assertEquals(1, orderCount());
    }

    @Test
    void sameKeyForAnotherQuoteIsRejected() throws Exception {
        place(OWNER, "key-1", issuedQuoteId(OWNER)).andExpect(status().isAccepted());

        place(OWNER, "key-1", issuedQuoteId(OWNER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void expiredQuoteIsRejectedForNewRequest() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        clock.set(NOW.plus(Duration.ofMinutes(5)));

        place(OWNER, "key-1", quoteId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_EXPIRED"));
        assertEquals(0, orderCount());
    }

    @Test
    void quoteOfAnotherUserIsNotFound() throws Exception {
        String quoteId = issuedQuoteId(OWNER);

        place(OTHER, "key-1", quoteId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_QUOTE_NOT_FOUND"));
    }

    @Test
    void getsOwnOrderOnly() throws Exception {
        String orderId = orderId(place(OWNER, "key-1", issuedQuoteId(OWNER)));

        mockMvc.perform(get("/api/v1/exchanges/{id}", orderId).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId));
        mockMvc.perform(get("/api/v1/exchanges/{id}", orderId).header("User-Id", OTHER).header("User-Role", "USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_ORDER_NOT_FOUND"));
    }

    @Test
    void listsOwnOrdersNewestFirstWithFilters() throws Exception {
        OrderIds ids = placeOrdersForList();

        list(OWNER, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ids.processingReExchange()))
                .andExpect(jsonPath("$.data.items[1].orderId").value(ids.failed()))
                .andExpect(jsonPath("$.data.items[2].orderId").value(ids.completed()))
                .andExpect(jsonPath("$.data.items[0].type").value("RE_EXCHANGE"))
                .andExpect(jsonPath("$.data.items[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.items[0].fromCurrency").value("JPY"))
                .andExpect(jsonPath("$.data.items[0].toCurrency").value("KRW"))
                .andExpect(jsonPath("$.data.items[0].settledAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].failureReason").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.items[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[2].totalDebitAmount").value("101000"))
                .andExpect(jsonPath("$.data.items[2].settledAt").exists());

        list(OWNER, "status=COMPLETED")
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ids.completed()));
        list(OWNER, "type=RE_EXCHANGE")
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ids.processingReExchange()));
        list(OWNER, "status=PROCESSING&type=EXCHANGE")
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void listsOrdersPageByPageWithoutOverlap() throws Exception {
        OrderIds ids = placeOrdersForList();

        list(OWNER, "size=2")
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ids.processingReExchange()))
                .andExpect(jsonPath("$.data.items[1].orderId").value(ids.failed()));
        list(OWNER, "size=2&page=1")
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ids.completed()));
        list(OWNER, "size=1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void listRejectsInvalidParameters() throws Exception {
        list(OWNER, "status=DONE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        list(OWNER, "page=-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        list(OWNER, "size=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    private record OrderIds(String completed, String failed, String processingReExchange) {
    }

    /**
     * 본인 주문 3건(1분 간격: 완료된 환전 → 실패한 환전 → 처리 중인 재환전)과 다른 사람의 가장 최근 주문 1건을 API로 만듭니다.
     * 견적 유효 시간(5분) 안에 신청하도록 시계를 옮기면서 견적을 발급합니다.
     */
    private OrderIds placeOrdersForList() throws Exception {
        ledger.postsSucceed();
        String completed = orderId(place(OWNER, "key-1", issuedQuoteId(OWNER)).andExpect(status().isOk()));

        clock.set(NOW.plus(Duration.ofMinutes(1)));
        ledger.respond(posting -> new LedgerPostResult.Rejected("LEDGER_REJECTED_INSUFFICIENT_BALANCE"));
        place(OWNER, "key-2", issuedQuoteId(OWNER)).andExpect(status().isUnprocessableEntity());
        // FAILED는 422 오류 응답이라 본문에 주문이 없어 DB에서 찾는다
        String failed = jdbcTemplate.queryForObject("SELECT order_id FROM " + SCHEMA
                + ".exchange_orders WHERE user_id = ? AND idempotency_key = 'key-2'", String.class, OWNER);

        clock.set(NOW.plus(Duration.ofMinutes(2)));
        ledger.respond(posting -> new LedgerPostResult.Unknown("test"));
        String reExchangeQuoteId = JsonPath.read(mockMvc.perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER)
                        .header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"JPY","toCurrency":"KRW","amount":"10000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.quoteId");
        String processing = orderId(place(OWNER, "key-3", reExchangeQuoteId).andExpect(status().isAccepted()));

        clock.set(NOW.plus(Duration.ofMinutes(3)));
        place(OTHER, "key-1", issuedQuoteId(OTHER)).andExpect(status().isAccepted());

        return new OrderIds(completed, failed, processing);
    }

    private ResultActions list(UUID userId, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/exchanges?" + query)
                .header("User-Id", userId)
                .header("User-Role", "USER"));
    }

    @Test
    void postedLedgerCompletesOrderWithSixEntries() throws Exception {
        ledger.postsSucceed();
        String quoteId = issuedQuoteId(OWNER);

        String orderId = orderId(place(OWNER, "key-1", quoteId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.settledAt").value(NOW.toString())));

        UUID id = UUID.fromString(orderId);
        Map<String, Object> row = orderRow(id);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(FakeLedger.transactionIdOf(id), row.get("ledger_transaction_id"));
        assertEquals(1, row.get("ledger_check_count"));
        assertEquals(List.of("PROCESSING", "COMPLETED"), historyStatuses(id));
        LedgerPosting posting = ledger.postings().getFirst();
        assertEquals(id, posting.orderId());
        assertEquals(OWNER, posting.userId());
        assertEquals(6, posting.entries().size());
    }

    @Test
    void rejectedLedgerFailsOrderAndRetryGetsSame422WithoutCallingLedger() throws Exception {
        ledger.respond(posting -> new LedgerPostResult.Rejected("LEDGER_REJECTED_INSUFFICIENT_BALANCE"));
        String quoteId = issuedQuoteId(OWNER);

        place(OWNER, "key-1", quoteId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_LEDGER_REJECTED_INSUFFICIENT_BALANCE"));
        place(OWNER, "key-1", quoteId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXCHANGE_LEDGER_REJECTED_INSUFFICIENT_BALANCE"));

        assertEquals(1, ledger.calls());
        UUID orderId = onlyOrderId();
        assertEquals("FAILED", orderRow(orderId).get("status"));
        assertEquals("INSUFFICIENT_BALANCE", orderRow(orderId).get("failure_reason"));
        mockMvc.perform(get("/api/v1/exchanges/{id}", orderId).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void conflictGoesToManualReviewAndIsLeftOutOfRecovery() throws Exception {
        ledger.respond(posting -> new LedgerPostResult.Invalid(LedgerPostResult.InvalidKind.CONFLICT));

        place(OWNER, "key-1", issuedQuoteId(OWNER))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"));

        UUID orderId = onlyOrderId();
        assertEquals(List.of("PROCESSING", "MANUAL_REVIEW"), historyStatuses(orderId));
        assertEquals("LEDGER_CONFLICT", jdbcTemplate.queryForObject("SELECT reason FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE to_status = 'MANUAL_REVIEW'", String.class));
        clock.set(NOW.plus(Duration.ofHours(1)));
        assertEquals(0, recoveryService.recover(10).found());
        assertEquals(1, ledger.calls());
    }

    @Test
    void unknownResultIsRecoveredLaterWithSameRequest() throws Exception {
        place(OWNER, "key-1", issuedQuoteId(OWNER))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        UUID orderId = onlyOrderId();

        // 확인 1회 뒤 대기 시간(1분)이 지나기 전에는 복구 대상이 아니다
        clock.set(NOW.plusSeconds(59));
        assertEquals(0, recoveryService.recover(10).found());

        ledger.postsSucceed();
        clock.set(NOW.plusSeconds(60));
        assertEquals(1, recoveryService.recover(10).settled());

        assertEquals("COMPLETED", orderRow(orderId).get("status"));
        assertEquals(2, orderRow(orderId).get("ledger_check_count"));
        assertEquals(2, ledger.calls());
        assertEquals(ledger.postings().get(0), ledger.postings().get(1));
        mockMvc.perform(get("/api/v1/exchanges/{id}", orderId).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    /**
     * 결과를 모르는 주문의 재요청은 원장을 부르지 않고 저장된 상태(202 PROCESSING)로 응답한다.
     * 원장이 살아나도 재요청으로는 확정하지 않고, 복구 스케줄러가 확정한 뒤에야 재요청이 확정 결과를 받는다.
     */
    @Test
    void retryOfProcessingOrderReturnsStoredStateWithoutCallingLedger() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());
        ledger.postsSucceed();

        clock.set(NOW.plusSeconds(30));
        place(OWNER, "key-1", quoteId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertEquals(1, ledger.calls());

        clock.set(NOW.plusSeconds(60));
        assertEquals(1, recoveryService.recover(10).settled());
        assertEquals(2, ledger.calls());

        place(OWNER, "key-1", quoteId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertEquals(2, ledger.calls());
    }

    /** 원장이 느려 사용자가 여러 번 누른 경우. 재요청이 몇 개 몰려도 원장 호출은 늘지 않는다 */
    @Test
    void concurrentRetriesDoNotCallLedger() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());
        UUID orderId = onlyOrderId();
        clock.set(NOW.plusSeconds(10));
        ledger.postsSucceed();

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return place(OWNER, "key-1", quoteId).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertEquals(202, result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, ledger.calls());
        assertEquals("PROCESSING", orderRow(orderId).get("status"));
        assertEquals(List.of("PROCESSING"), historyStatuses(orderId));
    }

    /** 24시간이 지나 사람 확인으로 보내는 것도 재요청이 아니라 복구 스케줄러가 한다 */
    @Test
    void orderUnconfirmedForADayGoesToManualReviewByRecoveryNotRetry() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());

        clock.set(NOW.plus(Duration.ofHours(24)));
        place(OWNER, "key-1", quoteId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        recoveryService.recover(10);
        place(OWNER, "key-1", quoteId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"));

        assertEquals(1, ledger.calls());
        assertEquals("LEDGER_UNCONFIRMED_TOO_LONG", jdbcTemplate.queryForObject("SELECT reason FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE to_status = 'MANUAL_REVIEW'", String.class));
    }

    @Test
    void orderUnconfirmedForADayStaysProcessingWhileAnotherFlowHoldsClaim() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());

        // 24시간 직전에 다른 흐름(복구 스케줄러)이 선점해 원장을 호출하는 중인 상황
        Instant dayLater = NOW.plus(Duration.ofHours(24));
        jdbcTemplate.update("UPDATE " + SCHEMA + ".exchange_orders SET last_ledger_checked_at = ?",
                Timestamp.from(dayLater.minusSeconds(5)));

        // 재요청은 원장 처리기를 부르지 않으므로, 처리기를 직접 불러 선점 중인 주문을 사람 확인으로 바꾸지 않는지 본다
        clock.set(dayLater);
        ExchangeOrder order = orderRepository.findById(onlyOrderId()).orElseThrow();
        assertEquals(ExchangeOrderStatus.PROCESSING, ledgerProcessor.process(order).getStatus());

        assertEquals(1, ledger.calls());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE to_status = 'MANUAL_REVIEW'", Integer.class));
    }

    private Map<String, Object> orderRow(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM " + SCHEMA + ".exchange_orders WHERE order_id = ?", orderId);
    }

    private List<String> historyStatuses(UUID orderId) {
        return jdbcTemplate.queryForList("SELECT to_status FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE order_id = ? ORDER BY created_at, to_status = 'PROCESSING' DESC",
                String.class, orderId);
    }

    private UUID onlyOrderId() {
        return jdbcTemplate.queryForObject("SELECT order_id FROM " + SCHEMA + ".exchange_orders", UUID.class);
    }

    private String issuedQuoteId(UUID userId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", userId)
                        .header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.quoteId");
    }

    private ResultActions place(UUID userId, String idempotencyKey, String quoteId) throws Exception {
        return mockMvc.perform(post("/api/v1/exchanges")
                .header("User-Id", userId)
                .header("User-Role", "USER")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quoteId":"%s","walletId":"%s"}
                        """.formatted(quoteId, WALLET_ID)));
    }

    private static String orderId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.orderId");
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_orders", Integer.class);
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

        @Bean
        @Primary
        FakeLedger testLedgerClient() {
            return new FakeLedger();
        }
    }

    /**
     * 원장 서비스 대신 쓰는 가짜 원장입니다. 테스트마다 응답을 바꾸고, 받은 요청을 기록합니다.
     * 진짜 원장처럼 같은 주문 ID는 한 번만 기록하고 같은 결과를 돌려줍니다(거래 ID를 주문 ID에서 만듦).
     */
    static final class FakeLedger implements LedgerClient {

        private final List<LedgerPosting> postings = new CopyOnWriteArrayList<>();
        private volatile Function<LedgerPosting, LedgerPostResult> responder;
        private volatile Function<UUID, LedgerLookupResult> lookupResponder =
                orderId -> new LedgerLookupResult.NotFound();

        @Override
        public LedgerPostResult post(LedgerPosting posting) {
            postings.add(posting);
            return responder.apply(posting);
        }

        @Override
        public LedgerLookupResult findByOrderId(UUID orderId) {
            return lookupResponder.apply(orderId);
        }

        void respondLookup(Function<UUID, LedgerLookupResult> lookupResponder) {
            this.lookupResponder = lookupResponder;
        }

        void respond(Function<LedgerPosting, LedgerPostResult> responder) {
            this.responder = responder;
        }

        void postsSucceed() {
            respond(posting -> new LedgerPostResult.Posted(transactionIdOf(posting.orderId())));
        }

        void reset() {
            postings.clear();
            respond(posting -> new LedgerPostResult.Unknown("test"));
            respondLookup(orderId -> new LedgerLookupResult.NotFound());
        }

        int calls() {
            return postings.size();
        }

        List<LedgerPosting> postings() {
            return List.copyOf(postings);
        }

        static UUID transactionIdOf(UUID orderId) {
            return UUID.nameUUIDFromBytes(("ledger-" + orderId).getBytes(StandardCharsets.UTF_8));
        }
    }
}

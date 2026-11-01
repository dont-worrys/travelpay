package com.travelpay.exchange;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPosting;
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

    @Test
    void retryWithinClaimLeaseDoesNotCallLedger() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());
        ledger.postsSucceed();

        // 첫 요청의 원장 호출이 아직 끝나지 않았을 수 있는 10초 안에는 원장을 부르지 않고 현재 상태로 응답한다
        clock.set(NOW.plusSeconds(10).minusMillis(1));
        place(OWNER, "key-1", quoteId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertEquals(1, ledger.calls());

        clock.set(NOW.plusSeconds(10));
        place(OWNER, "key-1", quoteId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertEquals(2, ledger.calls());
    }

    @Test
    void concurrentRetriesCallLedgerOnceAndSettleOnce() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());
        UUID orderId = onlyOrderId();
        // 첫 요청이 결과를 모른 채 끝나고 선점 유효 기간이 지난 뒤, 재요청 4개가 몰린 경우
        clock.set(NOW.plusSeconds(10));
        ledger.respond(posting -> {
            sleep(200);
            return new LedgerPostResult.Posted(FakeLedger.transactionIdOf(posting.orderId()));
        });

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
                int status = result.get(10, TimeUnit.SECONDS);
                assertTrue(status == 200 || status == 202, "status " + status);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, ledger.calls());
        assertEquals("COMPLETED", orderRow(orderId).get("status"));
        assertEquals(List.of("PROCESSING", "COMPLETED"), historyStatuses(orderId));
    }

    @Test
    void orderUnconfirmedForADayGoesToManualReviewOnRetry() throws Exception {
        String quoteId = issuedQuoteId(OWNER);
        place(OWNER, "key-1", quoteId).andExpect(status().isAccepted());

        clock.set(NOW.plus(Duration.ofHours(24)));
        place(OWNER, "key-1", quoteId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"));

        assertEquals(1, ledger.calls());
        assertEquals("LEDGER_UNCONFIRMED_TOO_LONG", jdbcTemplate.queryForObject("SELECT reason FROM " + SCHEMA
                + ".exchange_order_status_histories WHERE to_status = 'MANUAL_REVIEW'", String.class));
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

        @Override
        public LedgerPostResult post(LedgerPosting posting) {
            postings.add(posting);
            return responder.apply(posting);
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

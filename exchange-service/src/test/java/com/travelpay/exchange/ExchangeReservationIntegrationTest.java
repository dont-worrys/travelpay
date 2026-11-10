package com.travelpay.exchange;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.ArrayList;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.reservation.ExchangeReservationExecutor;
import com.travelpay.exchange.application.reservation.ReservationExecution;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
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
 * 실제 Postgres에 Flyway를 적용한 앱에서 예약 생성·재요청·조회·목록·취소(HTTP)와 체결을 검증합니다(#240).
 * 지갑 서비스는 고정된 지갑을 돌려주는 Reader로, 원장은 가짜 원장으로 바꿉니다. Docker가 없으면 건너뜁니다.
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

    @Autowired
    private ExchangeRateRepository rateRepository;

    @Autowired
    private ExchangeReservationExecutor executor;

    @Autowired
    private ExchangeOrderIntegrationTest.FakeLedger ledger;

    @Autowired
    private FakeWallet wallet;

    @Autowired
    private FakeRateProvider rateProvider;

    @Autowired
    private ExchangeRateCollector collector;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
        ledger.reset();
        wallet.reset();
        rateProvider.publishYen(null);
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

    /**
     * 조건에 닿은 환율(820 ≤ 목표 830)로 체결하면: 주문을 만들고 원장까지 처리하며, 예약은 주문 ID와 함께 EXECUTED가 된다.
     * 주문 생성 이력은 시스템이 만든 것으로 남고, 예약 상세에서 주문 상태가 보이며, 체결된 예약은 취소할 수 없다.
     */
    @Test
    void executesTriggeredReservationIntoCompletedOrder() throws Exception {
        String id = reservationId(reserve(OWNER, "key-1", "830.00"));
        ExchangeRate rate = savedYenRate("820.00");
        ledger.postsSucceed();
        clock.set(NOW.plus(Duration.ofHours(1)));

        ReservationExecution execution = executor.execute(UUID.fromString(id), rate);

        assertEquals(ReservationExecution.Outcome.EXECUTED, execution.outcome());
        UUID orderId = execution.order().orElseThrow().getId();
        Map<String, Object> reservation = jdbcTemplate.queryForMap(
                "SELECT * FROM " + SCHEMA + ".exchange_reservations WHERE reservation_id = ?", UUID.fromString(id));
        assertEquals("EXECUTED", reservation.get("status"));
        assertEquals(orderId, reservation.get("order_id"));
        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT * FROM " + SCHEMA + ".exchange_orders WHERE order_id = ?", orderId);
        assertEquals("COMPLETED", order.get("status"));
        assertEquals("reservation:" + id, order.get("idempotency_key"));
        assertEquals(12_195L, order.get("to_amount"));
        assertEquals(rate.getId(), order.get("rate_id"));
        assertEquals(List.of("PROCESSING:SYSTEM", "COMPLETED:SYSTEM"), jdbcTemplate.queryForList(
                "SELECT to_status || ':' || changed_by_type FROM " + SCHEMA + ".exchange_order_status_histories"
                        + " WHERE order_id = ? ORDER BY created_at, to_status DESC", String.class, orderId));
        assertEquals(1, ledger.calls());

        mockMvc.perform(get("/api/v1/exchange-reservations/{id}", id).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.orderStatus").value("COMPLETED"));
        cancel(OWNER, id).andExpect(status().isConflict());
    }

    /** 원장 결과를 모르면 주문은 PROCESSING으로 남지만 체결은 된 것이다. 이후는 보통 환전처럼 복구 스케줄러가 처리한다 */
    @Test
    void executionStaysExecutedWhenLedgerResultUnknown() throws Exception {
        String id = reservationId(reserve(OWNER, "key-1", "830.00"));

        ReservationExecution execution = executor.execute(UUID.fromString(id), savedYenRate("830.00"));

        assertEquals(ReservationExecution.Outcome.EXECUTED, execution.outcome());
        assertEquals("PROCESSING", jdbcTemplate.queryForObject("SELECT status FROM " + SCHEMA + ".exchange_orders",
                String.class));
        assertEquals("EXECUTED", reservationStatus(id));
    }

    /** 조건에서 벗어났거나, 취소됐거나, 만료 시각이 지났으면 아무것도 바꾸지 않는다 */
    @Test
    void skipsReservationThatCannotBeExecuted() throws Exception {
        String waiting = reservationId(reserve(OWNER, "key-1", "830.00"));
        String canceled = reservationId(reserve(OWNER, "key-2", "830.00"));
        cancel(OWNER, canceled).andExpect(status().isOk());
        ExchangeRate rate = savedYenRate("820.00");

        assertEquals(ReservationExecution.Outcome.SKIPPED,
                executor.execute(UUID.fromString(waiting), savedYenRate("830.01")).outcome());
        assertEquals(ReservationExecution.Outcome.SKIPPED, executor.execute(UUID.fromString(canceled), rate).outcome());
        clock.set(Instant.parse("2026-11-17T03:00:00Z"));
        assertEquals(ReservationExecution.Outcome.SKIPPED, executor.execute(UUID.fromString(waiting), rate).outcome());

        assertEquals("WAITING", reservationStatus(waiting));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_orders", Integer.class));
        assertEquals(0, ledger.calls());
    }

    /** 여러 인스턴스가 같은 예약을 동시에 체결하려 해도 주문은 하나다(먼저 잠근 쪽만 체결, 나머지는 SKIP LOCKED로 건너뜀) */
    @Test
    void concurrentExecutionCreatesSingleOrder() throws Exception {
        UUID id = UUID.fromString(reservationId(reserve(OWNER, "key-1", "830.00")));
        ExchangeRate rate = savedYenRate("820.00");
        ledger.postsSucceed();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationExecution>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return executor.execute(id, rate);
                }));
            }
            start.countDown();
            int executed = 0;
            for (Future<ReservationExecution> result : results) {
                if (result.get(30, TimeUnit.SECONDS).outcome() == ReservationExecution.Outcome.EXECUTED) {
                    executed++;
                }
            }
            assertEquals(1, executed);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_orders", Integer.class));
        assertEquals(1, ledger.calls());
        assertEquals("EXECUTED", reservationStatus(id.toString()));
    }

    /** 같은 통화의 환율은 하루 1건이라, 저장할 때마다 고시일을 하루씩 앞당긴다 */
    private int rateDays;

    /**
     * 전체 흐름: 환율을 수집해 저장하면 그 환율로 조건에 닿은 예약만 체결된다.
     * 엔화 820 수집 → 엔화 사기(목표 830, 820 ≤ 830) 체결, 엔화 팔기(목표 840, 820 < 840) 대기 유지.
     */
    @Test
    void collectingRateExecutesOnlyTriggeredReservations() throws Exception {
        String buy = reservationId(reserve(OWNER, "key-1", "830.00"));
        String sell = reservationId(mockMvc.perform(post("/api/v1/exchange-reservations")
                .header("User-Id", OWNER).header("User-Role", "USER").header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"walletId":"%s","fromCurrency":"JPY","toCurrency":"KRW","amount":"10000",
                         "targetRate":"840.00","expiresAt":"2026-11-17T03:00:00Z"}
                        """.formatted(WALLET_ID)))
                .andExpect(status().isCreated()));
        ledger.postsSucceed();
        rateProvider.publishYen("820.00");

        collector.collectLatestIfEmpty();

        assertEquals("EXECUTED", reservationStatus(buy));
        assertEquals("WAITING", reservationStatus(sell));
        assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT o.status FROM " + SCHEMA + ".exchange_orders o JOIN "
                + SCHEMA + ".exchange_reservations r ON r.order_id = o.order_id WHERE r.reservation_id = ?",
                String.class, UUID.fromString(buy)));
        assertEquals(1, ledger.calls());
    }

    /** 지갑이 해지돼 없으면 다시 해도 같으므로 사유를 남기고 취소한다. 사용자는 상세에서 사유를 본다 */
    @Test
    void cancelsReservationWhenWalletIsGone() throws Exception {
        String id = reservationId(reserve(OWNER, "key-1", "830.00"));
        wallet.answer(Optional::empty);

        assertEquals(ReservationExecution.Outcome.CANCELED,
                executor.execute(UUID.fromString(id), savedYenRate("820.00")).outcome());

        mockMvc.perform(get("/api/v1/exchange-reservations/{id}", id).header("User-Id", OWNER).header("User-Role", "USER"))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.cancelReason").value("WALLET_UNAVAILABLE"));
        assertEquals(0, ledger.calls());
    }

    /** 지갑 서비스 장애는 잠깐일 수 있어 대기로 남긴다. 복구된 뒤 다음 환율로 다시 체결된다 */
    @Test
    void keepsWaitingOnWalletOutageAndExecutesLater() throws Exception {
        UUID id = UUID.fromString(reservationId(reserve(OWNER, "key-1", "830.00")));
        wallet.answer(() -> {
            throw new WalletBalanceFetchException("wallet down");
        });

        assertEquals(ReservationExecution.Outcome.RETRY_LATER, executor.execute(id, savedYenRate("820.00")).outcome());
        assertEquals("WAITING", reservationStatus(id.toString()));

        wallet.reset();
        assertEquals(ReservationExecution.Outcome.EXECUTED, executor.execute(id, savedYenRate("825.00")).outcome());
    }

    private ExchangeRate savedYenRate(String midRate) {
        BigDecimal mid = new BigDecimal(midRate);
        return rateRepository.save(ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100, mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                LocalDate.of(2026, 11, 10).minusDays(rateDays++), RateSource.KOREAEXIM), clock.instant()));
    }

    private String reservationStatus(String id) {
        return jdbcTemplate.queryForObject("SELECT status FROM " + SCHEMA
                + ".exchange_reservations WHERE reservation_id = ?", String.class, UUID.fromString(id));
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

        /** 외부 API 대신 씁니다. 기본은 빈 목록이라 기동 시 수집이 아무것도 저장하지 않습니다. */
        @Bean
        @Primary
        FakeRateProvider testRateProvider() {
            return new FakeRateProvider();
        }

        /** 누가 조회하든 WALLET_ID인 ACTIVE 지갑(원화·엔화 계정)을 돌려줍니다. */
        @Bean
        @Primary
        ExchangeOrderIntegrationTest.FakeLedger testLedgerClient() {
            return new ExchangeOrderIntegrationTest.FakeLedger();
        }

        @Bean
        @Primary
        FakeWallet testWalletBalanceReader() {
            return new FakeWallet();
        }
    }

    /** 수집할 환율을 테스트가 정합니다. 고시일은 수집기가 요청한 날짜입니다. */
    static final class FakeRateProvider implements ExchangeRateProvider {

        private volatile String yenMidRate;

        @Override
        public RateSource source() {
            return RateSource.KOREAEXIM;
        }

        @Override
        public List<CollectedRate> fetch(LocalDate date) {
            String mid = yenMidRate;
            if (mid == null) {
                return List.of();
            }
            BigDecimal rate = new BigDecimal(mid);
            return List.of(new CollectedRate(ForeignCurrency.JPY, 100, rate, rate.multiply(new BigDecimal("1.01")),
                    rate.multiply(new BigDecimal("0.99")), date, RateSource.KOREAEXIM));
        }

        void publishYen(String midRate) {
            this.yenMidRate = midRate;
        }
    }

    /** 기본은 누가 조회하든 WALLET_ID인 ACTIVE 지갑(원화·엔화 계정)입니다. 테스트마다 응답을 바꿉니다. */
    static final class FakeWallet implements WalletBalanceReader {

        private volatile Supplier<Optional<WalletBalances>> answer = FakeWallet::active;

        @Override
        public Optional<WalletBalances> read(UUID userId) {
            return answer.get();
        }

        void answer(Supplier<Optional<WalletBalances>> answer) {
            this.answer = answer;
        }

        void reset() {
            answer = FakeWallet::active;
        }

        private static Optional<WalletBalances> active() {
            return Optional.of(new WalletBalances(WALLET_ID, true, Map.of(
                    Currency.KRW, new WalletBalances.Account(new Money(Currency.KRW, 500_000), true),
                    Currency.JPY, new WalletBalances.Account(new Money(Currency.JPY, 0), true))));
        }
    }
}

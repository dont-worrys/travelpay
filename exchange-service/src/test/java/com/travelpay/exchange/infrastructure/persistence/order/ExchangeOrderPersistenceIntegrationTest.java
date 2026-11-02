package com.travelpay.exchange.infrastructure.persistence.order;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.travelpay.exchange.domain.order.ChangedByType;
import com.travelpay.exchange.domain.order.DuplicateExchangeOrderException;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.order.Settlement;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres(Flyway V3)에서 주문 저장소를 검증합니다. UNIQUE 위반 변환, 동시 저장, JPA로 UPDATE되지 않는지 확인합니다.
 * Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.enabled=false"
})
class ExchangeOrderPersistenceIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Duration LEASE = Duration.ofSeconds(10);

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
    private ExchangeOrderRepository orderRepository;

    @Autowired
    private ExchangeOrderStatusHistoryRepository historyRepository;

    @Autowired
    private ExchangeQuoteRepository quoteRepository;

    @Autowired
    private ExchangeRateRepository rateRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExchangeRate jpyRate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        jpyRate = rateRepository.save(ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100,
                new BigDecimal("838.30"), new BigDecimal("846.68"), new BigDecimal("829.91"),
                LocalDate.of(2026, 10, 28), RateSource.KOREAEXIM), Instant.parse("2026-10-28T02:10:00Z")));
    }

    @Test
    void savesSnapshotAndFindsByIdempotencyKeyAndOwner() {
        ExchangeQuote quote = savedQuote(USER_ID);

        ExchangeOrder saved = orderRepository.save(OrderFixtures.placed(quote, WALLET_ID, "key-1"));
        historyRepository.save(saved.placedHistory());

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals(saved.getId(), orderRepository
                .findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey("key-1")).orElseThrow().getId());
        assertTrue(orderRepository.findByIdAndUserId(saved.getId(), USER_ID).isPresent());
        assertTrue(orderRepository.findByIdAndUserId(saved.getId(), OTHER_USER_ID).isEmpty());
        assertTrue(orderRepository.findByUserIdAndIdempotencyKey(OTHER_USER_ID, new IdempotencyKey("key-1"))
                .isEmpty());

        Map<String, Object> history = jdbcTemplate.queryForMap("SELECT from_status, to_status, changed_by_type FROM "
                + SCHEMA + ".exchange_order_status_histories WHERE order_id = ?", saved.getId());
        assertEquals(null, history.get("from_status"));
        assertEquals(ExchangeOrderStatus.PROCESSING.name(), history.get("to_status"));
        assertEquals(ChangedByType.USER.name(), history.get("changed_by_type"));
    }

    @Test
    void sameQuoteWithAnotherKeyIsDuplicate() {
        ExchangeQuote quote = savedQuote(USER_ID);
        orderRepository.save(OrderFixtures.placed(quote, WALLET_ID, "key-1"));

        assertThrows(DuplicateExchangeOrderException.class,
                () -> orderRepository.save(OrderFixtures.placed(quote, WALLET_ID, "key-2")));
        assertEquals(1, orderCount());
    }

    @Test
    void sameKeyWithAnotherQuoteIsDuplicate() {
        orderRepository.save(OrderFixtures.placed(savedQuote(USER_ID), WALLET_ID, "key-1"));

        assertThrows(DuplicateExchangeOrderException.class,
                () -> orderRepository.save(OrderFixtures.placed(savedQuote(USER_ID), WALLET_ID, "key-1")));
        assertEquals(1, orderCount());
    }

    @Test
    void sameKeyOfAnotherUserIsNotDuplicate() {
        orderRepository.save(OrderFixtures.placed(savedQuote(USER_ID), WALLET_ID, "key-1"));
        orderRepository.save(OrderFixtures.placed(savedQuote(OTHER_USER_ID), WALLET_ID, "key-1"));

        assertEquals(2, orderCount());
    }

    @Test
    void concurrentOrdersWithSameQuoteSaveOnlyOne() throws Exception {
        ExchangeQuote quote = savedQuote(USER_ID);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "key-" + i;
                Callable<Boolean> task = () -> {
                    start.await();
                    try {
                        orderRepository.save(OrderFixtures.placed(quote, WALLET_ID, key));
                        return true;
                    } catch (DuplicateExchangeOrderException e) {
                        return false;
                    }
                };
                results.add(executor.submit(task));
            }
            start.countDown();

            int saved = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    saved++;
                }
            }
            assertEquals(1, saved);
            assertEquals(1, orderCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSettleChangesOrderOnlyOnce() throws Exception {
        UUID orderId = savedOrder("key-1");
        UUID ledgerTransactionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-01T03:00:00Z");

        int changed = countTrue(4, () -> orderRepository.settleIfProcessing(orderId,
                Settlement.completed(ledgerTransactionId, now), now));

        assertEquals(1, changed);
        Map<String, Object> row = orderRow(orderId);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(ledgerTransactionId, row.get("ledger_transaction_id"));
        assertEquals(now, ((Timestamp) row.get("settled_at")).toInstant());
        assertEquals(now, ((Timestamp) row.get("updated_at")).toInstant());
    }

    @Test
    void settledOrderIsNotChangedAgain() {
        UUID orderId = savedOrder("key-1");
        Instant now = Instant.parse("2026-11-01T03:00:00Z");
        orderRepository.settleIfProcessing(orderId, Settlement.completed(UUID.randomUUID(), now), now);

        boolean changed = orderRepository.settleIfProcessing(orderId,
                Settlement.failed(ExchangeFailureReason.INSUFFICIENT_BALANCE, now), now);

        assertFalse(changed);
        assertEquals("COMPLETED", orderRow(orderId).get("status"));
        assertEquals(ExchangeOrderStatus.COMPLETED, orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void manualReviewHasNoSettledTime() {
        UUID orderId = savedOrder("key-1");

        assertTrue(orderRepository.settleIfProcessing(orderId,
                Settlement.manualReview(ManualReviewReason.LEDGER_CONFLICT), Instant.now()));

        Map<String, Object> row = orderRow(orderId);
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertNull(row.get("settled_at"));
        assertNull(row.get("ledger_transaction_id"));
    }

    @Test
    void concurrentClaimGivesLedgerCheckToOnlyOne() throws Exception {
        UUID orderId = savedOrder("key-1");
        Instant now = Instant.parse("2026-11-01T03:00:00Z");

        int claimed = countTrue(4, () -> orderRepository.claimLedgerCheck(orderId, 0, now, LEASE));

        assertEquals(1, claimed);
        ExchangeOrder order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, order.getLedgerCheckCount());
        assertEquals(now, order.getLastLedgerCheckedAt());
    }

    @Test
    void claimNeedsSeenCountAndProcessing() {
        UUID orderId = savedOrder("key-1");
        Instant now = Instant.parse("2026-11-01T03:00:00Z");

        assertFalse(orderRepository.claimLedgerCheck(orderId, 1, now, LEASE));
        assertTrue(orderRepository.claimLedgerCheck(orderId, 0, now, LEASE));
        orderRepository.settleIfProcessing(orderId, Settlement.completed(UUID.randomUUID(), now), now);
        assertFalse(orderRepository.claimLedgerCheck(orderId, 1, now.plus(LEASE), LEASE));
    }

    @Test
    void claimIsBlockedUntilLeaseEndsEvenWithLatestCount() {
        UUID orderId = savedOrder("key-1");
        Instant first = Instant.parse("2026-11-01T03:00:00Z");
        assertTrue(orderRepository.claimLedgerCheck(orderId, 0, first, LEASE));

        // 앞 흐름이 선점한 뒤 주문을 읽은 흐름: 횟수는 최신 값(1)이지만 유효 기간 안이라 선점하지 못한다
        assertFalse(orderRepository.claimLedgerCheck(orderId, 1, first.plus(LEASE).minusMillis(1), LEASE));
        assertEquals(1, orderRepository.findById(orderId).orElseThrow().getLedgerCheckCount());

        // 유효 기간이 끝나는 순간부터는 선점할 수 있다
        assertTrue(orderRepository.claimLedgerCheck(orderId, 1, first.plus(LEASE), LEASE));
        assertEquals(2, orderRepository.findById(orderId).orElseThrow().getLedgerCheckCount());
    }

    @Test
    void recoveryTargetsWaitLongerAsChecksIncrease() {
        Instant now = Instant.parse("2026-11-01T03:00:00Z");
        UUID neverCheckedDue = processingOrder("a", 0, null, now.minusSeconds(31));
        processingOrder("b", 0, null, now.minusSeconds(10));
        UUID checkedOnceDue = processingOrder("c", 1, now.minusSeconds(61), now.minusSeconds(120));
        processingOrder("d", 1, now.minusSeconds(50), now.minusSeconds(120));
        UUID manyChecksCapped = processingOrder("e", 7, now.minus(Duration.ofMinutes(16)).minusSeconds(1),
                now.minus(Duration.ofHours(2)));
        processingOrder("f", 7, now.minus(Duration.ofMinutes(15)), now.minus(Duration.ofHours(2)));
        UUID manualReview = processingOrder("g", 0, null, now.minus(Duration.ofHours(30)));
        orderRepository.settleIfProcessing(manualReview,
                Settlement.manualReview(ManualReviewReason.LEDGER_CONFLICT), now);

        List<UUID> targets = orderRepository.findRecoveryTargets(now, 10).stream().map(ExchangeOrder::getId).toList();

        assertEquals(List.of(neverCheckedDue, checkedOnceDue, manyChecksCapped), targets);
        assertEquals(1, orderRepository.findRecoveryTargets(now, 1).size());
    }

    @Test
    void jpaDoesNotUpdateOrderEvenIfEntityChanges() {
        ExchangeOrder saved = orderRepository.save(OrderFixtures.placed(savedQuote(USER_ID), WALLET_ID, "key-1"));
        Map<String, Object> before = orderRow(saved.getId());

        // 상태는 조건부 UPDATE로만 바꾼다. 엔티티 값을 바꿔 커밋해도 더티 체킹이 UPDATE를 보내지 않아야 한다.
        transactionTemplate.executeWithoutResult(status -> {
            ExchangeOrder loaded = orderRepository.findByIdAndUserId(saved.getId(), USER_ID).orElseThrow();
            ReflectionTestUtils.setField(loaded, "status", ExchangeOrderStatus.COMPLETED);
            ReflectionTestUtils.setField(loaded, "ledgerTransactionId", UUID.randomUUID());
            ReflectionTestUtils.setField(loaded, "fromAmount", 1L);
        });

        assertEquals(before, orderRow(saved.getId()));
    }

    /** DB에 저장된 견적입니다. 저장하면 ID와 발급 시각(created_at)이 생깁니다. */
    private ExchangeQuote savedQuote(UUID userId) {
        return quoteRepository.save(OrderFixtures.wonToYenQuote(userId, jpyRate, Instant.now()));
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + SCHEMA + ".exchange_orders", Integer.class);
    }

    private Map<String, Object> orderRow(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT status, ledger_transaction_id, from_amount, settled_at, updated_at FROM "
                + SCHEMA + ".exchange_orders WHERE order_id = ?", orderId);
    }

    private UUID savedOrder(String idempotencyKey) {
        return orderRepository.save(OrderFixtures.placed(savedQuote(USER_ID), WALLET_ID, idempotencyKey)).getId();
    }

    /** 확인 횟수·마지막 확인 시각·신청 시각을 정해 둔 PROCESSING 주문입니다. */
    private UUID processingOrder(String idempotencyKey, int checkCount, Instant lastCheckedAt, Instant requestedAt) {
        UUID orderId = savedOrder(idempotencyKey);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".exchange_orders SET ledger_check_count = ?,"
                        + " last_ledger_checked_at = ?, requested_at = ? WHERE order_id = ?",
                checkCount, lastCheckedAt == null ? null : Timestamp.from(lastCheckedAt), Timestamp.from(requestedAt),
                orderId);
        return orderId;
    }

    /** 여러 스레드가 동시에 call을 실행해 true를 돌려준 수입니다. */
    private static int countTrue(int threads, Callable<Boolean> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            int count = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    count++;
                }
            }
            return count;
        } finally {
            executor.shutdownNow();
        }
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

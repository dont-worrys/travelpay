package com.travelpay.exchange.infrastructure.persistence.reservation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.application.reservation.ExchangeReservationQueryRepository;
import com.travelpay.exchange.domain.reservation.DuplicateExchangeReservationException;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import com.travelpay.exchange.domain.reservation.ReservationPolicy;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres(Flyway V7)에서 예약 저장과 DB 제약, 조건 매칭·만료 조회가 부분 인덱스를 쓸 수 있는지 확인합니다(#240).
 * Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.enabled=false"
})
class ExchangeReservationPersistenceIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final ReservationPolicy POLICY = new ReservationPolicy(Duration.ofDays(30), 10);

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
    private ExchangeReservationRepository repository;

    @Autowired
    private ExchangeReservationQueryRepository queryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, "
                + SCHEMA + ".exchange_order_status_histories, " + SCHEMA + ".exchange_orders");
    }

    @Test
    void savesWaitingReservationAndFindsOnlyByOwner() {
        ExchangeReservation saved = repository.save(reservation(USER_ID, "key-1"));

        ExchangeReservation found = repository.findByIdAndUserId(saved.getId(), USER_ID).orElseThrow();
        assertEquals(ReservationStatus.WAITING, found.getStatus());
        assertEquals(Currency.KRW, found.getFromCurrency());
        assertEquals(Currency.JPY, found.getToCurrency());
        assertEquals(100_000L, found.getFromAmount());
        assertEquals(0, new BigDecimal("830").compareTo(found.getTargetRate()));
        assertEquals(100, found.getRateUnit());
        assertNotNull(found.getCreatedAt());
        assertTrue(repository.findByIdAndUserId(saved.getId(), OTHER_USER_ID).isEmpty());
    }

    /** 같은 회원·같은 멱등 키의 예약은 하나만 저장되고, 저장소가 도메인 예외로 알린다 (다른 회원은 같은 키를 써도 됨) */
    @Test
    void sameKeyOfSameUserIsDuplicate() {
        UUID first = repository.save(reservation(USER_ID, "key-1")).getId();
        repository.save(reservation(OTHER_USER_ID, "key-1"));

        assertThrows(DuplicateExchangeReservationException.class, () -> repository.save(reservation(USER_ID, "key-1")));
        assertEquals(first, repository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey("key-1"))
                .orElseThrow().getId());
        assertTrue(repository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey("key-2")).isEmpty());
    }

    /** 대기 예약 수는 그 회원의 WAITING만 센다 */
    @Test
    void countsOnlyWaitingReservationsOfUser() {
        repository.save(reservation(USER_ID, "key-1"));
        UUID canceled = repository.save(reservation(USER_ID, "key-2")).getId();
        repository.save(reservation(OTHER_USER_ID, "key-1"));
        repository.cancelIfWaiting(canceled, USER_ID, Instant.now());

        assertEquals(1, repository.countWaitingByUserId(USER_ID));
        assertEquals(1, repository.countWaitingByUserId(OTHER_USER_ID));
    }

    /** 취소는 본인의 대기 예약에만 한 번 반영된다. 다른 회원·이미 끝난 예약은 바꾸지 않는다 */
    @Test
    void cancelsOnlyOwnWaitingReservationOnce() {
        UUID id = repository.save(reservation(USER_ID, "key-1")).getId();
        UUID expired = repository.save(reservation(USER_ID, "key-2")).getId();
        jdbcTemplate.update("UPDATE " + SCHEMA + ".exchange_reservations SET status = 'EXPIRED' WHERE reservation_id = ?",
                expired);
        Instant canceledAt = Instant.parse("2026-11-10T05:00:00Z");

        assertFalse(repository.cancelIfWaiting(id, OTHER_USER_ID, canceledAt), "다른 회원은 취소 못 함");
        assertTrue(repository.cancelIfWaiting(id, USER_ID, canceledAt));
        assertFalse(repository.cancelIfWaiting(id, USER_ID, canceledAt), "이미 취소됨");
        assertFalse(repository.cancelIfWaiting(expired, USER_ID, canceledAt), "만료된 예약");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT status, updated_at FROM " + SCHEMA
                + ".exchange_reservations WHERE reservation_id = ?", id);
        assertEquals("CANCELED", row.get("status"));
        assertEquals(canceledAt, ((Timestamp) row.get("updated_at")).toInstant());
    }

    /** 같은 예약을 동시에 여러 번 취소해도 한 번만 성공한다 (체결과 겹칠 때도 같은 원리로 한쪽만 반영) */
    @Test
    void concurrentCancelSucceedsOnce() throws Exception {
        UUID id = repository.save(reservation(USER_ID, "key-1")).getId();

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return repository.cancelIfWaiting(id, USER_ID, Instant.now());
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(1, succeeded);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 목록은 본인 예약만, 상태 필터를 적용하고, pageable 정렬(생성 최신순)을 따른다 */
    @Test
    void listsOwnReservationsWithStatusFilterNewestFirst() {
        UUID oldest = savedAt(USER_ID, "key-1", "2026-11-10T01:00:00Z");
        UUID middle = savedAt(USER_ID, "key-2", "2026-11-10T02:00:00Z");
        UUID newest = savedAt(USER_ID, "key-3", "2026-11-10T03:00:00Z");
        savedAt(OTHER_USER_ID, "key-1", "2026-11-10T04:00:00Z");
        repository.cancelIfWaiting(middle, USER_ID, Instant.now());
        PageRequest firstPage = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        assertEquals(List.of(newest, middle, oldest), ids(queryRepository.findPage(USER_ID, null, firstPage)));
        assertEquals(List.of(newest, oldest),
                ids(queryRepository.findPage(USER_ID, ReservationStatus.WAITING, firstPage)));
        assertEquals(List.of(middle), ids(queryRepository.findPage(USER_ID, ReservationStatus.CANCELED, firstPage)));

        Page<ExchangeReservation> secondOfTwo = queryRepository.findPage(USER_ID, null,
                PageRequest.of(1, 2, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        assertEquals(List.of(oldest), ids(secondOfTwo));
        assertEquals(3, secondOfTwo.getTotalElements());
    }

    private UUID savedAt(UUID userId, String key, String createdAt) {
        UUID id = repository.save(reservation(userId, key)).getId();
        jdbcTemplate.update("UPDATE " + SCHEMA + ".exchange_reservations SET created_at = ? WHERE reservation_id = ?",
                Timestamp.from(Instant.parse(createdAt)), id);
        return id;
    }

    private static List<UUID> ids(Page<ExchangeReservation> page) {
        return page.getContent().stream().map(ExchangeReservation::getId).toList();
    }

    /** 체결 상태와 주문 연결은 함께 기록된다: 주문 없이 EXECUTED가 되거나, WAITING에 주문이 붙을 수 없다 */
    @Test
    void databaseKeepsExecutedStatusAndOrderTogether() {
        UUID id = repository.save(reservation(USER_ID, "key-1")).getId();

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + ".exchange_reservations SET status = 'EXECUTED' WHERE reservation_id = ?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + ".exchange_reservations SET executed_at = now() WHERE reservation_id = ?", id));
    }

    /**
     * 조건 매칭(통화쌍 같음 + 목표 환율 범위)과 만료 조회가 대기 예약만 담은 부분 인덱스를 쓴다.
     * 빈 테이블이면 대기 예약만 담은 두 부분 인덱스의 비용이 같아 아무거나 고를 수 있어,
     * 실제처럼 대기 예약을 쌓고 통계를 갱신한 뒤 본다. 순차 읽기는 꺼서 인덱스끼리의 선택만 본다.
     */
    @Test
    void matchingAndExpiryQueriesUsePartialIndexes() {
        seedWaitingReservations(5000);

        String buyMatch = explainWithoutSeqScan("SELECT reservation_id FROM " + SCHEMA + ".exchange_reservations"
                + " WHERE status = 'WAITING' AND from_currency = 'KRW' AND to_currency = 'JPY' AND target_rate >= 830");
        String sellMatch = explainWithoutSeqScan("SELECT reservation_id FROM " + SCHEMA + ".exchange_reservations"
                + " WHERE status = 'WAITING' AND from_currency = 'JPY' AND to_currency = 'KRW' AND target_rate <= 830");
        String expiry = explainWithoutSeqScan("SELECT reservation_id FROM " + SCHEMA + ".exchange_reservations"
                + " WHERE status = 'WAITING' AND expires_at <= now()");

        assertTrue(buyMatch.contains("ix_exchange_reservations_waiting_match"), buyMatch);
        assertTrue(sellMatch.contains("ix_exchange_reservations_waiting_match"), sellMatch);
        assertTrue(expiry.contains("ix_exchange_reservations_waiting_expiry"), expiry);
    }

    /** 통화쌍·목표 환율·만료 시각이 고르게 퍼진 대기 예약을 넣고 통계를 갱신합니다. 만료 시각은 1~30일 뒤라 지금 만료된 예약은 없습니다. */
    private void seedWaitingReservations(int count) {
        jdbcTemplate.update("""
                INSERT INTO exchange_service.exchange_reservations (reservation_id, user_id, wallet_id, idempotency_key,
                    from_currency, to_currency, from_amount, target_rate, rate_unit, status, expires_at, created_at, updated_at)
                SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'seed-' || i,
                       CASE WHEN i % 2 = 0 THEN 'KRW' ELSE 'JPY' END, CASE WHEN i % 2 = 0 THEN 'JPY' ELSE 'KRW' END,
                       100000, 780 + (i % 100), 100, 'WAITING', now() + (1 + i % 30) * interval '1 day', now(), now()
                  FROM generate_series(1, ?) AS i
                """, count);
        jdbcTemplate.execute("ANALYZE exchange_service.exchange_reservations");
    }

    private String explainWithoutSeqScan(String query) {
        return transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + query, String.class));
        });
    }

    /**
     * 엔화 매매기준율이 830일 때: 엔화를 사는 예약은 목표 830 이상, 파는 예약은 830 이하가 체결 대상이다(같으면 양쪽 다).
     * 다른 외화(위안화), 대기가 아닌 예약, 만료 시각이 지난 대기 예약은 빠지고, 먼저 예약한 순서로 나온다.
     */
    @Test
    void findsTriggeredWaitingReservationsInReservedOrder() {
        Instant now = Instant.parse("2026-11-10T03:00:00Z");
        UUID buyAbove = matchTarget("buy-840", Currency.KRW, Currency.JPY, "840", now, "2026-11-10T01:00:00Z");
        UUID sellEqual = matchTarget("sell-830", Currency.JPY, Currency.KRW, "830", now, "2026-11-10T01:30:00Z");
        UUID buyEqual = matchTarget("buy-830", Currency.KRW, Currency.JPY, "830", now, "2026-11-10T02:00:00Z");
        UUID sellBelow = matchTarget("sell-820", Currency.JPY, Currency.KRW, "820", now, "2026-11-10T02:30:00Z");
        matchTarget("buy-820", Currency.KRW, Currency.JPY, "820", now, "2026-11-10T01:00:00Z");
        matchTarget("sell-840", Currency.JPY, Currency.KRW, "840", now, "2026-11-10T01:00:00Z");
        matchTarget("cny-buy", Currency.KRW, Currency.CNY, "900", now, "2026-11-10T01:00:00Z");
        UUID canceled = matchTarget("canceled", Currency.KRW, Currency.JPY, "850", now, "2026-11-10T01:00:00Z");
        repository.cancelIfWaiting(canceled, USER_ID, now);
        UUID expired = matchTarget("expired", Currency.KRW, Currency.JPY, "850", now, "2026-11-10T01:00:00Z");
        jdbcTemplate.update("UPDATE " + SCHEMA + ".exchange_reservations SET expires_at = ? WHERE reservation_id = ?",
                Timestamp.from(now), expired);

        List<UUID> triggered = repository.findTriggeredWaitingIds(Currency.JPY, new BigDecimal("830.00"), now);

        assertEquals(List.of(buyAbove, sellEqual, buyEqual, sellBelow), triggered);
    }

    /** 실제 매칭 SQL(사는 쪽·파는 쪽 UNION ALL)의 두 조회가 모두 대기 예약 부분 인덱스를 쓴다 */
    @Test
    void triggeredQueryUsesWaitingMatchIndexForBothSides() {
        seedWaitingReservations(5000);

        String plan = explainWithoutSeqScan("""
                SELECT m.reservation_id FROM (
                    SELECT reservation_id, created_at FROM exchange_service.exchange_reservations
                     WHERE status = 'WAITING' AND from_currency = 'KRW' AND to_currency = 'JPY'
                       AND target_rate >= 830 AND expires_at > now()
                    UNION ALL
                    SELECT reservation_id, created_at FROM exchange_service.exchange_reservations
                     WHERE status = 'WAITING' AND from_currency = 'JPY' AND to_currency = 'KRW'
                       AND target_rate <= 830 AND expires_at > now()
                ) m ORDER BY m.created_at, m.reservation_id
                """);

        assertEquals(2, plan.split("ix_exchange_reservations_waiting_match", -1).length - 1, plan);
    }

    private UUID matchTarget(String key, Currency from, Currency to, String targetRate, Instant now, String createdAt) {
        long amount = from == Currency.KRW ? 100_000 : 10_000;
        ExchangeReservation reservation = ExchangeReservation.reserve(USER_ID, WALLET_ID, new IdempotencyKey(key),
                new QuoteCurrencies(from, to), new Money(from, amount), new BigDecimal(targetRate),
                now.plus(Duration.ofDays(7)), QuoteFixtures.policy(), POLICY, now);
        UUID id = repository.save(reservation).getId();
        jdbcTemplate.update("UPDATE " + SCHEMA + ".exchange_reservations SET created_at = ? WHERE reservation_id = ?",
                Timestamp.from(Instant.parse(createdAt)), id);
        return id;
    }

    private static ExchangeReservation reservation(UUID userId, String key) {
        Instant now = Instant.now();
        return ExchangeReservation.reserve(userId, WALLET_ID, new IdempotencyKey(key),
                new QuoteCurrencies(Currency.KRW, Currency.JPY), new Money(Currency.KRW, 100_000),
                new BigDecimal("830"), now.plus(Duration.ofDays(7)), QuoteFixtures.policy(), POLICY, now);
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

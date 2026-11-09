package com.travelpay.exchange.infrastructure.persistence.reservation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.RateSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** 같은 회원·같은 멱등 키의 예약은 하나만 저장된다 (다른 회원은 같은 키를 써도 됨) */
    @Test
    void sameKeyOfSameUserIsRejected() {
        repository.save(reservation(USER_ID, "key-1"));
        repository.save(reservation(OTHER_USER_ID, "key-1"));

        assertThrows(DataIntegrityViolationException.class, () -> repository.save(reservation(USER_ID, "key-1")));
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

package com.travelpay.exchange.infrastructure.persistence.order;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.application.order.ExchangeOrderListCondition;
import com.travelpay.exchange.application.order.ExchangeOrderQueryRepository;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderType;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.order.Settlement;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuoteType;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres에서 환전 주문 목록 조회(본인 주문만, 상태·유형 필터, 최신순 고정 정렬, 페이지 경계)를 검증합니다.
 * Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.enabled=false"
})
class ExchangeOrderListQueryIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant T0 = Instant.parse("2026-11-01T03:00:00Z");
    /** 이슈 #78 정렬: 신청(생성) 최신순, 같으면 주문 ID 역순 */
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    /**
     * PostgreSQL의 UUID 순서(바이트 단위, 부호 없음)입니다. Java UUID.compareTo는 부호 있는 long으로 비교해 앞자리가 8~f면 순서가 다릅니다.
     * 소문자 16진 문자열 순서는 바이트 순서와 같습니다.
     */
    private static final Comparator<UUID> DB_UUID_ORDER = Comparator.comparing(UUID::toString);

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
    private ExchangeOrderQueryRepository queryRepository;

    @Autowired
    private ExchangeOrderRepository orderRepository;

    @Autowired
    private ExchangeQuoteRepository quoteRepository;

    @Autowired
    private ExchangeRateRepository rateRepository;

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
    void ownOrdersOnlyNewestFirst() {
        UUID oldest = order(OWNER, Currency.KRW, Currency.JPY, T0);
        UUID middle = order(OWNER, Currency.JPY, Currency.KRW, T0.plusSeconds(60));
        UUID newest = order(OWNER, Currency.KRW, Currency.JPY, T0.plusSeconds(120));
        order(OTHER, Currency.KRW, Currency.JPY, T0.plusSeconds(180));

        Page<ExchangeOrder> page = page(new ExchangeOrderListCondition(OWNER, null, null), 0, 10);

        assertEquals(List.of(newest, middle, oldest), ids(page));
        assertEquals(3, page.getTotalElements());
    }

    @Test
    void sameCreatedTimeIsOrderedByIdDescending() {
        List<UUID> sameTime = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sameTime.add(order(OWNER, Currency.KRW, Currency.JPY, T0));
        }
        UUID newer = order(OWNER, Currency.KRW, Currency.JPY, T0.plusSeconds(1));

        List<UUID> expected = new ArrayList<>();
        expected.add(newer);
        sameTime.stream().sorted(DB_UUID_ORDER.reversed()).forEach(expected::add);
        assertEquals(expected, ids(page(new ExchangeOrderListCondition(OWNER, null, null), 0, 10)));
    }

    @Test
    void filtersByType() {
        UUID exchange = order(OWNER, Currency.KRW, Currency.JPY, T0);
        UUID reExchange = order(OWNER, Currency.JPY, Currency.KRW, T0.plusSeconds(60));

        assertEquals(List.of(exchange),
                ids(page(new ExchangeOrderListCondition(OWNER, null, ExchangeOrderType.EXCHANGE), 0, 10)));
        assertEquals(List.of(reExchange),
                ids(page(new ExchangeOrderListCondition(OWNER, null, ExchangeOrderType.RE_EXCHANGE), 0, 10)));
    }

    @Test
    void filtersByStatusAndCombinedWithType() {
        UUID completedExchange = order(OWNER, Currency.KRW, Currency.JPY, T0);
        UUID failedExchange = order(OWNER, Currency.KRW, Currency.JPY, T0.plusSeconds(60));
        UUID completedReExchange = order(OWNER, Currency.JPY, Currency.KRW, T0.plusSeconds(120));
        UUID processing = order(OWNER, Currency.KRW, Currency.JPY, T0.plusSeconds(180));
        orderRepository.settleIfProcessing(completedExchange, Settlement.completed(UUID.randomUUID(), T0), T0);
        orderRepository.settleIfProcessing(failedExchange,
                Settlement.failed(ExchangeFailureReason.INSUFFICIENT_BALANCE, T0), T0);
        orderRepository.settleIfProcessing(completedReExchange, Settlement.completed(UUID.randomUUID(), T0), T0);

        assertEquals(List.of(completedReExchange, completedExchange),
                ids(page(new ExchangeOrderListCondition(OWNER, ExchangeOrderStatus.COMPLETED, null), 0, 10)));
        assertEquals(List.of(processing),
                ids(page(new ExchangeOrderListCondition(OWNER, ExchangeOrderStatus.PROCESSING, null), 0, 10)));
        assertEquals(List.of(completedExchange), ids(page(new ExchangeOrderListCondition(OWNER,
                ExchangeOrderStatus.COMPLETED, ExchangeOrderType.EXCHANGE), 0, 10)));
        assertTrue(page(new ExchangeOrderListCondition(OWNER, ExchangeOrderStatus.MANUAL_REVIEW, null), 0, 10)
                .isEmpty());
    }

    @Test
    void pagesDoNotRepeatOrSkipOrdersWithSameCreatedTime() {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(order(OWNER, Currency.KRW, Currency.JPY, T0));
        }
        ExchangeOrderListCondition condition = new ExchangeOrderListCondition(OWNER, null, null);

        List<UUID> collected = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            Page<ExchangeOrder> page = page(condition, pageNumber, 2);
            assertEquals(5, page.getTotalElements());
            assertEquals(3, page.getTotalPages());
            collected.addAll(ids(page));
        }

        assertEquals(all.stream().sorted(DB_UUID_ORDER.reversed()).toList(), collected);
    }

    @Test
    void noOrdersIsEmptyPage() {
        Page<ExchangeOrder> page = page(new ExchangeOrderListCondition(OWNER, null, null), 0, 20);

        assertTrue(page.isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    /**
     * 목록 쿼리가 회원 인덱스 ix_exchange_orders_user_created(user_id, created_at DESC)를 쓸 수 있는 모양인지 실행 계획으로 확인합니다.
     * 테스트 데이터는 몇 건뿐이라 PostgreSQL이 테이블 전체 읽기(Seq Scan)를 고르므로, 이 연결에서만 전체 읽기를 꺼 두고 확인합니다.
     * 그래서 "많은 데이터에서 인덱스를 고른다"가 아니라 "조건·정렬이 인덱스와 맞는다"를 확인합니다(user_id 조건이 빠지는 등의 회귀를 잡음).
     * EXPLAIN은 JPQL이 아닌 SQL로 해야 해서 findPage와 같은 조건·정렬의 SQL을 씁니다. null인 조건은 생략된 모양입니다.
     */
    @Test
    void listQueryUsesUserCreatedIndex() {
        order(OWNER, Currency.KRW, Currency.JPY, T0);
        order(OTHER, Currency.KRW, Currency.JPY, T0.plusSeconds(60));

        String noFilter = """
                select * from exchange_orders
                 where user_id = '%s'
                 order by created_at desc, order_id desc
                 limit 20 offset 0
                """.formatted(OWNER);
        String statusAndReExchange = """
                select * from exchange_orders
                 where user_id = '%s' and status = 'COMPLETED' and to_currency = 'KRW'
                 order by created_at desc, order_id desc
                 limit 20 offset 0
                """.formatted(OWNER);

        for (String query : List.of(noFilter, statusAndReExchange)) {
            String plan = explainWithoutSeqScan(query);
            assertTrue(plan.contains("ix_exchange_orders_user_created"), plan);
        }
    }

    private String explainWithoutSeqScan(String query) {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (ResultSet rows = statement.executeQuery("EXPLAIN " + query)) {
                    StringBuilder plan = new StringBuilder();
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                    return plan.toString();
                }
            } finally {
                // 풀에 돌아간 연결을 다른 테스트가 쓰므로 원래대로 되돌린다
                try (Statement statement = connection.createStatement()) {
                    statement.execute("RESET enable_seqscan");
                }
            }
        });
    }

    private Page<ExchangeOrder> page(ExchangeOrderListCondition condition, int page, int size) {
        return queryRepository.findPage(condition, PageRequest.of(page, size, LIST_SORT));
    }

    private static List<UUID> ids(Page<ExchangeOrder> page) {
        return page.getContent().stream().map(ExchangeOrder::getId).toList();
    }

    /** 회원의 PROCESSING 주문을 저장하고, 정렬을 확인하려고 생성 시각을 정해 둡니다. 원화 10만 원 또는 엔화 1,000엔 견적입니다. */
    private UUID order(UUID userId, Currency from, Currency to, Instant createdAt) {
        Money amount = from == Currency.KRW ? new Money(Currency.KRW, 100_000) : new Money(Currency.JPY, 1_000);
        ExchangeQuote quote = quoteRepository.save(ExchangeQuote.issue(userId, QuoteType.USER,
                new QuoteCurrencies(from, to), amount, jpyRate, QuoteFixtures.policy(), Instant.now()));
        UUID orderId = orderRepository.save(OrderFixtures.placed(quote, WALLET_ID, "key-" + UUID.randomUUID()))
                .getId();
        jdbcTemplate.update("UPDATE " + SCHEMA + ".exchange_orders SET created_at = ? WHERE order_id = ?",
                Timestamp.from(createdAt), orderId);
        return orderId;
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

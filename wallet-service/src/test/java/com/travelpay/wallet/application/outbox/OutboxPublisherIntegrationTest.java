package com.travelpay.wallet.application.outbox;

import com.travelpay.wallet.infra.kafka.KafkaOutboxMessageSender;
import com.travelpay.wallet.infra.kafka.WalletKafkaTopicConfig;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.hibernate.SessionEventListener;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres와 Kafka로 Outbox 발행기를 검증한다. 스케줄러는 끄고 발행 메서드를 직접 호출한다.
 * 토픽은 테스트끼리 공유하므로 메시지는 이번 테스트가 넣은 eventId로만 센다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // Hibernate 통계에는 batch 실행 횟수가 없어 세션 이벤트로 센다
        "spring.jpa.properties.hibernate.session.events.auto="
                + "com.travelpay.wallet.application.outbox.OutboxPublisherIntegrationTest$JdbcBatchCounter"
})
class OutboxPublisherIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final String TOPIC = WalletKafkaTopicConfig.CHARGE_REQUESTED;
    private static final int BATCH_SIZE = 50;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 세션마다 Hibernate가 만든다. 발행기 외 JPA 세션이 없도록 스케줄러·컨슈머는 꺼 둔 상태에서만 센다 */
    public static class JdbcBatchCounter implements SessionEventListener {
        static final AtomicInteger BATCHES = new AtomicInteger();

        @Override
        public void jdbcExecuteBatchStart() {
            BATCHES.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".outbox_events");
    }

    @Test
    void PENDING_1건을_발행하면_토픽에_1건_보내고_PUBLISHED로_바꾼다() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = insertPending(aggregateId, Instant.now());

        int published = publisher.publishPending(BATCH_SIZE);

        assertEquals(1, published);
        Map<String, Object> row = outboxRow(eventId);
        assertEquals("PUBLISHED", row.get("status"));
        assertNotNull(row.get("published_at"));
        assertEquals(0, row.get("retry_count"));

        List<ConsumerRecord<String, String>> records = consume(Set.of(eventId), 1);
        assertEquals(1, records.size());
        assertEquals(aggregateId.toString(), records.getFirst().key());
        assertEquals(eventId.toString(), eventIdOf(records.getFirst()));
    }

    @Test
    void Kafka가_멈추면_PENDING으로_남기고_재시도_횟수를_올리며_복구_후_발행한다() {
        UUID eventId = insertPending(UUID.randomUUID(), Instant.now());

        pauseKafka();
        try {
            assertEquals(0, publisher.publishPending(BATCH_SIZE));
            Map<String, Object> row = outboxRow(eventId);
            assertEquals("PENDING", row.get("status"));
            assertNull(row.get("published_at"));
            assertEquals(1, row.get("retry_count"));
        } finally {
            unpauseKafka();
        }

        // 브로커 재접속 시간이 걸릴 수 있어 몇 번 다시 호출한다
        Instant deadline = Instant.now().plusSeconds(60);
        while (publisher.publishPending(BATCH_SIZE) == 0 && Instant.now().isBefore(deadline)) {
            // 다음 주기처럼 다시 호출
        }

        assertEquals("PUBLISHED", outboxRow(eventId).get("status"));
        // 멈춘 동안 시간 초과로 실패 처리한 요청이 브로커 재개 후 기록될 수 있어 중복은 허용 (같은 eventId로 다시 발행되며, 컨슈머는 중복을 제거하지 않고 실행기가 현재 상태로 흡수한다.)
        List<ConsumerRecord<String, String>> records = consume(Set.of(eventId), 1);
        assertTrue(records.size() >= 1);
        records.forEach(r -> assertEquals(eventId.toString(), eventIdOf(r)));
    }

    @Test
    void 발행기_2개가_동시에_실행해도_같은_이벤트를_함께_선점하지_않는다() throws Exception {
        Set<UUID> eventIds = new HashSet<>();
        Instant base = Instant.now();
        for (int i = 0; i < 20; i++) {
            eventIds.add(insertPending(UUID.randomUUID(), base.plusMillis(i)));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return publisher.publishPending(10);
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return publisher.publishPending(10);
            });
            start.countDown();

            assertEquals(10, first.get());
            assertEquals(10, second.get());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(20, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'", Integer.class));
        List<ConsumerRecord<String, String>> records = consume(eventIds, eventIds.size());
        assertEquals(20, records.size());
        Set<String> received = new HashSet<>();
        records.forEach(r -> received.add(eventIdOf(r)));
        assertEquals(20, received.size());
    }

    @Test
    void 발행_후_상태_변경이_실패하면_다음_호출에서_같은_eventId로_다시_발행한다() {
        UUID eventId = insertPending(UUID.randomUUID(), Instant.now());

        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_outbox_publish() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'outbox 상태 변경 실패 (테스트)';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_outbox_publish BEFORE UPDATE ON outbox_events
                FOR EACH ROW WHEN (NEW.status = 'PUBLISHED') EXECUTE FUNCTION fail_outbox_publish()
                """);
        try {
            assertThrows(RuntimeException.class, () -> publisher.publishPending(BATCH_SIZE));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_outbox_publish ON outbox_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_outbox_publish()");
        }

        Map<String, Object> rolledBack = outboxRow(eventId);
        assertEquals("PENDING", rolledBack.get("status"));
        assertEquals(0, rolledBack.get("retry_count"));

        assertEquals(1, publisher.publishPending(BATCH_SIZE));
        assertEquals("PUBLISHED", outboxRow(eventId).get("status"));

        List<ConsumerRecord<String, String>> records = consume(Set.of(eventId), 2);
        assertEquals(2, records.size());
        assertEquals(eventId.toString(), eventIdOf(records.get(0)));
        assertEquals(eventId.toString(), eventIdOf(records.get(1)));
    }

    @Test
    void 상태_변경_시간_초과로_롤백되면_batch를_보정해_다음_주기에_뒤_행을_발행한다() {
        Instant base = Instant.now();
        UUID first = insertPending(UUID.randomUUID(), base);
        UUID second = insertPending(UUID.randomUUID(), base.plusMillis(1));
        UUID later = insertPending(UUID.randomUUID(), base.plusMillis(2));
        OutboxPublishScheduler scheduler = new OutboxPublishScheduler(publisher, 2);

        // PUBLISHED UPDATE를 트랜잭션 제한(10초)보다 오래 멈춰 SQL 취소 → 롤백. 시퀀스는 롤백되지 않아 트리거 실행 증거로 쓴다
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS slow_outbox_publish_hits");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION slow_outbox_publish() RETURNS trigger AS $$
                BEGIN
                    PERFORM nextval('slow_outbox_publish_hits');
                    PERFORM pg_sleep(12);
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER slow_outbox_publish BEFORE UPDATE ON outbox_events
                FOR EACH ROW WHEN (NEW.status = 'PUBLISHED') EXECUTE FUNCTION slow_outbox_publish()
                """);
        try {
            scheduler.publish();
            assertTrue(jdbcTemplate.queryForObject(
                    "SELECT is_called FROM slow_outbox_publish_hits", Boolean.class));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS slow_outbox_publish ON outbox_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS slow_outbox_publish()");
            jdbcTemplate.execute("DROP SEQUENCE IF EXISTS slow_outbox_publish_hits");
        }

        for (UUID id : List.of(first, second)) {
            Map<String, Object> row = outboxRow(id);
            assertEquals("PENDING", row.get("status"));
            assertEquals(1, row.get("retry_count"));
        }
        assertEquals("PENDING", outboxRow(later).get("status"));
        assertEquals(0, outboxRow(later).get("retry_count"));

        scheduler.publish();

        assertEquals("PUBLISHED", outboxRow(later).get("status"));
        List<ConsumerRecord<String, String>> records = consume(Set.of(later), 1);
        assertEquals(1, records.size());
        assertEquals(later.toString(), eventIdOf(records.getFirst()));
    }

    @Test
    void 특정_행_때문에_롤백이_반복돼도_보정으로_새_정상_행은_계속_발행한다() {
        Instant base = Instant.now();
        UUID bad = insertPending(UUID.randomUUID(), base);
        UUID first = insertPending(UUID.randomUUID(), base.plusMillis(1));
        OutboxPublishScheduler scheduler = new OutboxPublishScheduler(publisher, 1);

        // 불량 행의 PUBLISHED UPDATE만 실패시킨다. 시퀀스는 롤백되지 않아 트리거 실행(=롤백) 횟수로 쓴다
        jdbcTemplate.execute("CREATE SEQUENCE bad_outbox_row_hits");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_bad_outbox_row() RETURNS trigger AS $$
                BEGIN
                    PERFORM nextval('bad_outbox_row_hits');
                    RAISE EXCEPTION 'outbox 불량 행 상태 변경 실패 (테스트)';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_bad_outbox_row BEFORE UPDATE ON outbox_events
                FOR EACH ROW WHEN (NEW.status = 'PUBLISHED' AND OLD.id = '%s'::uuid)
                EXECUTE FUNCTION fail_bad_outbox_row()
                """.formatted(bad));
        try {
            // 1회차: 불량 행 선택 → 롤백 → 보정으로 retry 1
            scheduler.publish();
            assertEquals(1L, badRowFailures());
            assertBadRow(bad, 1);
            assertEquals("PENDING", outboxRow(first).get("status"));
            assertEquals(0, outboxRow(first).get("retry_count"));

            // 2회차: retry 0인 A 선택 → 발행
            scheduler.publish();
            assertEquals(1L, badRowFailures());
            assertEquals("PUBLISHED", outboxRow(first).get("status"));
            assertEquals(List.of(first.toString()), eventIdsOf(consume(Set.of(first), 1)));

            // 3회차: retry 0인 행이 없어 불량 행 재선택 → 두 번째 롤백 → retry 2
            scheduler.publish();
            assertEquals(2L, badRowFailures());
            assertBadRow(bad, 2);

            // 4회차: 새 정상 행 B(retry 0)가 불량 행보다 먼저 선택 → 발행
            UUID second = insertPending(UUID.randomUUID(), Instant.now());
            scheduler.publish();
            assertEquals(2L, badRowFailures());
            assertEquals("PUBLISHED", outboxRow(second).get("status"));
            assertEquals(List.of(second.toString()), eventIdsOf(consume(Set.of(second), 1)));
            assertBadRow(bad, 2);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_bad_outbox_row ON outbox_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_bad_outbox_row()");
            jdbcTemplate.execute("DROP SEQUENCE IF EXISTS bad_outbox_row_hits");
        }
    }

    private long badRowFailures() {
        Map<String, Object> seq = jdbcTemplate.queryForMap("SELECT last_value, is_called FROM bad_outbox_row_hits");
        return Boolean.TRUE.equals(seq.get("is_called")) ? ((Number) seq.get("last_value")).longValue() : 0;
    }

    private void assertBadRow(UUID bad, int retryCount) {
        Map<String, Object> row = outboxRow(bad);
        assertEquals("PENDING", row.get("status"));
        assertNull(row.get("published_at"));
        assertEquals(retryCount, row.get("retry_count"));
    }

    private static List<String> eventIdsOf(List<ConsumerRecord<String, String>> records) {
        return records.stream().map(OutboxPublisherIntegrationTest::eventIdOf).toList();
    }

    @Test
    void 보정은_지정한_id_중_잠기지_않은_PENDING만_올린다() throws Exception {
        Instant base = Instant.now();
        UUID pending = insertPending(UUID.randomUUID(), base);
        UUID published = insertPending(UUID.randomUUID(), base.plusMillis(1));
        jdbcTemplate.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = now() WHERE id = ?", published);
        UUID locked = insertPending(UUID.randomUUID(), base.plusMillis(2));
        UUID notTarget = insertPending(UUID.randomUUID(), base.plusMillis(3));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> otherPublisher = executor.submit(() -> tx.executeWithoutResult(s -> {
                jdbcTemplate.queryForList("SELECT id FROM outbox_events WHERE id = ? FOR UPDATE", locked);
                lockAcquired.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(lockAcquired.await(10, TimeUnit.SECONDS));

            Instant startedAt = Instant.now();
            int updated = publisher.increaseRetryCountIfPending(List.of(pending, published, locked));
            Duration elapsed = Duration.between(startedAt, Instant.now());

            release.countDown();
            otherPublisher.get(30, TimeUnit.SECONDS);

            assertEquals(1, updated);
            // 잠긴 행을 기다리지 않고 건너뜀
            assertTrue(elapsed.compareTo(Duration.ofSeconds(3)) < 0, "elapsed=" + elapsed);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, outboxRow(pending).get("retry_count"));
        assertEquals("PUBLISHED", outboxRow(published).get("status"));
        assertEquals(0, outboxRow(published).get("retry_count"));
        assertEquals(0, outboxRow(locked).get("retry_count"));
        assertEquals(0, outboxRow(notTarget).get("retry_count"));
    }

    @Test
    void 대기_120건의_상태_변경은_JDBC_batch_50건씩_3번으로_나간다() {
        int count = 120;
        Set<UUID> eventIds = new HashSet<>();
        Instant base = Instant.now();
        for (int i = 0; i < count; i++) {
            eventIds.add(insertPending(UUID.randomUUID(), base.plusMillis(i)));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JdbcBatchCounter.BATCHES.set(0);

        assertEquals(count, publisher.publishPending(count));

        assertEquals(count, statistics.getEntityUpdateCount());
        // 50 + 50 + 20
        assertEquals(3, JdbcBatchCounter.BATCHES.get());
        // 조회 1 + UPDATE 문장 1 (batch마다 다시 준비하지 않음)
        assertEquals(2, statistics.getPrepareStatementCount());
        assertEquals(count, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'", Integer.class));
        assertEquals(count, consume(eventIds, count).size());
    }

    private UUID insertPending(UUID aggregateId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at)
                VALUES (?, 'CHARGE', ?, ?, ?::jsonb, 'PENDING', 0, ?)
                """, id, aggregateId, TOPIC, "{\"chargeId\":\"" + aggregateId + "\"}", Timestamp.from(createdAt));
        return id;
    }

    private Map<String, Object> outboxRow(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM outbox_events WHERE id = ?", id);
    }

    /** 토픽을 처음부터 읽어 eventId가 일치하는 메시지를 모은다. expected건을 받은 뒤에도 잠시 더 읽어 중복을 확인한다 */
    private List<ConsumerRecord<String, String>> consume(Set<UUID> eventIds, int expected) {
        Set<String> ids = new HashSet<>();
        eventIds.forEach(id -> ids.add(id.toString()));
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plusSeconds(30);
            Instant settleUntil = null;
            while (Instant.now().isBefore(deadline) && (settleUntil == null || Instant.now().isBefore(settleUntil))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (ids.contains(eventIdOf(record))) {
                        matched.add(record);
                    }
                }
                if (settleUntil == null && matched.size() >= expected) {
                    settleUntil = Instant.now().plusSeconds(2);
                }
            }
        }
        return matched;
    }

    private static String eventIdOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaOutboxMessageSender.EVENT_ID_HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unpauseKafka() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
}

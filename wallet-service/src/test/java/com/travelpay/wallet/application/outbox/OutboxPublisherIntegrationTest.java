package com.travelpay.wallet.application.outbox;

import com.travelpay.wallet.infra.kafka.KafkaOutboxMessageSender;
import com.travelpay.wallet.infra.kafka.WalletKafkaTopicConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "travelpay.wallet.charge.saga.consumer.enabled=false"
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
        // 멈춘 동안 시간 초과로 실패 처리한 요청이 브로커 재개 후 기록될 수 있어 중복은 허용 (같은 eventId로 컨슈머가 제거)
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

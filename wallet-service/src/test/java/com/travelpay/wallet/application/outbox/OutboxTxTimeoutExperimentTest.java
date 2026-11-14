package com.travelpay.wallet.application.outbox;

import com.travelpay.wallet.infra.kafka.KafkaOutboxMessageSender;
import com.travelpay.wallet.infra.kafka.WalletKafkaTopicConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.sql.SQLException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실험용: 발행 트랜잭션(timeout=10)에서 UPDATE flush·커밋이 10초를 넘기면 어떻게 되는지 관찰한다.
 * 단언 대신 결과를 [EXP] 줄로 출력한다. 트리거 실행 여부는 롤백과 무관한 시퀀스로 센다.
 * 실행: ./gradlew :wallet-service:performanceTest --tests "*OutboxTxTimeoutExperimentTest"
 */
@Tag("performance")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false"
})
class OutboxTxTimeoutExperimentTest {

    private static final String SCHEMA = "wallet_service";
    private static final String TOPIC = WalletKafkaTopicConfig.CHARGE_REQUESTED;
    private static final AtomicLong RESPONSE_DELAY_MS = new AtomicLong();

    private static final String SELECT_BATCH = """
            SELECT id FROM outbox_events WHERE status = 'PENDING'
             ORDER BY retry_count ASC, created_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED
            """;

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

    /** 실제 Kafka로 보내고, 응답(ack) 완료만 RESPONSE_DELAY_MS만큼 늦춘다 */
    @TestConfiguration
    static class DelayedSenderConfig {
        @Bean
        @Primary
        OutboxMessageSender delayedSender(KafkaOutboxMessageSender real) {
            return (eventType, key, eventId, payload) -> {
                long delay = RESPONSE_DELAY_MS.get();
                CompletableFuture<Void> sent = real.send(eventType, key, eventId, payload);
                if (delay == 0) {
                    return sent;
                }
                return sent.thenCompose(v -> CompletableFuture.runAsync(() -> { },
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)));
            };
        }
    }

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".outbox_events");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS exp_trigger_enter");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS exp_trigger_exit");
        jdbcTemplate.execute("CREATE SEQUENCE exp_trigger_enter");
        jdbcTemplate.execute("CREATE SEQUENCE exp_trigger_exit");
        RESPONSE_DELAY_MS.set(0);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS exp_sleep ON outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS exp_sleep()");
        RESPONSE_DELAY_MS.set(0);
    }

    @Test
    void 경우1_응답_7_5초_지연_후_UPDATE에서_4초_sleep() {
        runCase("CASE1", 7_500, 4, false);
    }

    @Test
    void 경우2_응답_지연_없이_UPDATE에서_12초_sleep() {
        runCase("CASE2", 0, 12, false);
    }

    /** 보조: UPDATE는 바로 끝나고, 커밋 시점에 실행되는 지연 제약 트리거에서 sleep */
    @Test
    void 경우3_보조_응답_7_5초_지연_후_커밋_시점에_4초_sleep() {
        runCase("CASE3", 7_500, 4, true);
    }

    /** 방법 E 사전 검증: 다른 발행기가 앞쪽 행을 잠근 상태에서 E의 UPDATE가 어떤 행을 올리는지 */
    @Test
    void 방법E_다른_발행기가_앞쪽을_잠그면_다른_행이_올라간다() throws Exception {
        Instant base = Instant.now();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(insertPending(base.plusMillis(i)));
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<UUID>> other = executor.submit(() -> tx.execute(s -> {
                List<UUID> got = jdbcTemplate.queryForList(SELECT_BATCH, UUID.class, 2);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return got;
            }));
            locked.await(10, TimeUnit.SECONDS);
            Instant t0 = Instant.now();
            Integer updated = tx.execute(s -> jdbcTemplate.update("""
                    UPDATE outbox_events SET retry_count = retry_count + 1
                     WHERE id IN (SELECT id FROM outbox_events WHERE status = 'PENDING'
                                   ORDER BY retry_count ASC, created_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED)
                    """, 2));
            long eMs = Duration.between(t0, Instant.now()).toMillis();
            release.countDown();
            List<UUID> otherLocked = other.get(30, TimeUnit.SECONDS);
            log("E", "otherPublisherLocked=" + index(ids, otherLocked) + " eUpdatedRows=" + updated + " eElapsedMs=" + eMs);
            for (int i = 0; i < ids.size(); i++) {
                log("E", "row" + i + " " + rowState(ids.get(i)));
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void runCase(String name, long responseDelayMs, int sleepSeconds, boolean atCommit) {
        Instant base = Instant.now();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(insertPending(base.plusMillis(i)));
        }
        createSleepTrigger(sleepSeconds, atCommit);
        RESPONSE_DELAY_MS.set(responseDelayMs);

        for (int i = 0; i < ids.size(); i++) {
            log(name, "before row" + i + " " + rowState(ids.get(i)));
        }

        Instant start = Instant.now();
        Throwable thrown = null;
        Integer published = null;
        try {
            published = publisher.publishPending(2);
        } catch (Throwable t) {
            thrown = t;
        }
        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        RESPONSE_DELAY_MS.set(0);

        log(name, "elapsedMs=" + elapsedMs + " published=" + published);
        log(name, "triggerEnter=" + seq("exp_trigger_enter") + " triggerExit=" + seq("exp_trigger_exit"));
        if (thrown != null) {
            int depth = 0;
            for (Throwable c = thrown; c != null && depth < 15; c = c.getCause(), depth++) {
                String sqlState = c instanceof SQLException se ? " sqlState=" + se.getSQLState() : "";
                log(name, "cause[" + depth + "] " + c.getClass().getName() + sqlState + " msg=" + oneLine(c.getMessage()));
                if (c instanceof SQLException se && se.getNextException() != null) {
                    SQLException n = se.getNextException();
                    log(name, "  next " + n.getClass().getName() + " sqlState=" + n.getSQLState() + " msg=" + oneLine(n.getMessage()));
                }
            }
        } else {
            log(name, "no exception");
        }
        for (int i = 0; i < ids.size(); i++) {
            log(name, "after row" + i + " " + rowState(ids.get(i)));
        }

        // 방법 E 사전 검증: 롤백 직후 같은 조건으로 다시 고르면 원래 batch가 나오는지
        List<UUID> reselected = new TransactionTemplate(transactionManager)
                .execute(s -> jdbcTemplate.queryForList(SELECT_BATCH, UUID.class, 2));
        log(name, "reselectAfter=" + index(ids, reselected));

        // 첫 두 건(batch)의 Kafka 수신을 실제 eventId로 확인 (트리거 제거 후 추가 발행은 하지 않음)
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS exp_sleep ON outbox_events");
        Map<String, Integer> received = consumeCounts(new HashSet<>(ids));
        for (int i = 0; i < ids.size(); i++) {
            log(name, "kafka row" + i + " received=" + received.getOrDefault(ids.get(i).toString(), 0));
        }
    }

    private void createSleepTrigger(int seconds, boolean atCommit) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION exp_sleep() RETURNS trigger AS $$
                BEGIN
                    PERFORM nextval('exp_trigger_enter');
                    PERFORM pg_sleep(%d);
                    PERFORM nextval('exp_trigger_exit');
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(seconds));
        if (atCommit) {
            jdbcTemplate.execute("""
                    CREATE CONSTRAINT TRIGGER exp_sleep AFTER UPDATE ON outbox_events
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW WHEN (NEW.status = 'PUBLISHED') EXECUTE FUNCTION exp_sleep()
                    """);
        } else {
            // 행마다 sleep한다. 첫 행만으로 남은 시간을 넘기도록 sleep 값을 잡는다 (enter/exit 시퀀스로 몇 행 실행됐는지 확인)
            jdbcTemplate.execute("""
                    CREATE TRIGGER exp_sleep BEFORE UPDATE ON outbox_events
                    FOR EACH ROW WHEN (NEW.status = 'PUBLISHED') EXECUTE FUNCTION exp_sleep()
                    """);
        }
    }

    private long seq(String name) {
        // 시퀀스는 롤백되지 않는다. 한 번도 안 불렸으면 is_called=false
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT last_value, is_called FROM " + name);
        return Boolean.TRUE.equals(row.get("is_called")) ? ((Number) row.get("last_value")).longValue() : 0;
    }

    private UUID insertPending(Instant createdAt) {
        UUID id = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at)
                VALUES (?, 'CHARGE', ?, ?, ?::jsonb, 'PENDING', 0, ?)
                """, id, aggregateId, TOPIC, "{\"chargeId\":\"" + aggregateId + "\"}", Timestamp.from(createdAt));
        return id;
    }

    private String rowState(UUID id) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, retry_count, published_at FROM outbox_events WHERE id = ?", id);
        return "status=" + row.get("status") + " retry=" + row.get("retry_count") + " publishedAt=" + row.get("published_at");
    }

    private static List<Integer> index(List<UUID> ids, List<UUID> picked) {
        List<Integer> result = new ArrayList<>();
        picked.forEach(p -> result.add(ids.indexOf(p)));
        return result;
    }

    private Map<String, Integer> consumeCounts(Set<UUID> eventIds) {
        Set<String> wanted = new HashSet<>();
        eventIds.forEach(id -> wanted.add(id.toString()));
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "exp-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        Map<String, Integer> counts = new java.util.HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plusSeconds(8);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    Header h = record.headers().lastHeader(KafkaOutboxMessageSender.EVENT_ID_HEADER);
                    String id = h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
                    if (id != null && wanted.contains(id)) {
                        counts.merge(id, 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ");
    }

    private static void log(String name, String msg) {
        System.out.println("[EXP][" + name + "] " + msg);
    }
}

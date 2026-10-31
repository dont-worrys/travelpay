package com.travelpay.wallet.application.outbox;

import com.travelpay.wallet.infra.kafka.WalletKafkaTopicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PENDING 조회 순서(재시도 적은 순 → 오래된 순)를 실제 Postgres로 검증한다. Kafka 대신 보내기를 대역으로 바꾼다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class OutboxPublishOrderIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final int BATCH_SIZE = 2;
    private static final int RETRIED_COUNT = 5;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @MockitoBean
    private OutboxMessageSender messageSender;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> retriedIds = new ArrayList<>();
    private final List<UUID> newIds = new ArrayList<>();

    /** 반복 실패한 오래된 건(batch-size보다 많이)과 그 뒤에 들어온 신규 건을 넣는다 */
    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".outbox_events");
        when(messageSender.send(anyString(), any(), any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < RETRIED_COUNT; i++) {
            retriedIds.add(insertPending(3, base.plusMillis(i)));
        }
        newIds.add(insertPending(0, Instant.now()));
    }

    @Test
    void 반복_실패_건이_batch_size_이상이어도_신규_건을_먼저_발행한다() {
        assertEquals(1, publisher.publishPending(1));

        assertEquals("PUBLISHED", outboxRow(newIds.getFirst()).get("status"));
        for (UUID id : retriedIds) {
            Map<String, Object> row = outboxRow(id);
            assertEquals("PENDING", row.get("status"));
            assertEquals(3, row.get("retry_count"));
            verify(messageSender, never()).send(anyString(), any(), eq(id), anyString());
        }
    }

    @Test
    void 재시도_건도_이후_조회에서_다시_처리한다() {
        int published = 0;
        for (int i = 0; i < 10 && published < newIds.size() + retriedIds.size(); i++) {
            published += publisher.publishPending(BATCH_SIZE);
        }

        assertEquals(newIds.size() + retriedIds.size(), published);
        for (UUID id : retriedIds) {
            assertEquals("PUBLISHED", outboxRow(id).get("status"));
            assertEquals(3, outboxRow(id).get("retry_count"));
        }
    }

    @Test
    void 이번_조회에서_실패한_건은_PENDING으로_남고_다음_조회에서_다시_발행한다() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".outbox_events");
        UUID eventId = insertPending(0, Instant.now());
        when(messageSender.send(anyString(), any(), eq(eventId), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("보내기 실패 (테스트)")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(0, publisher.publishPending(BATCH_SIZE));
        Map<String, Object> failed = outboxRow(eventId);
        assertEquals("PENDING", failed.get("status"));
        assertEquals(1, failed.get("retry_count"));

        assertEquals(1, publisher.publishPending(BATCH_SIZE));
        Map<String, Object> published = outboxRow(eventId);
        assertEquals("PUBLISHED", published.get("status"));
        assertEquals(1, published.get("retry_count"));
    }

    private UUID insertPending(int retryCount, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at)
                VALUES (?, 'CHARGE', ?, ?, '{}'::jsonb, 'PENDING', ?, ?)
                """, id, UUID.randomUUID(), WalletKafkaTopicConfig.CHARGE_REQUESTED, retryCount,
                Timestamp.from(createdAt));
        return id;
    }

    private Map<String, Object> outboxRow(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM outbox_events WHERE id = ?", id);
    }
}

package com.travelpay.wallet.infra.persistence;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 트랜잭션 단계들은 격리 수준을 @Transactional에 적지 않고 spring.datasource.hikari.transaction-isolation으로 정한다.
 * DB 기본값을 SERIALIZABLE로 바꾼 Postgres에서도 트랜잭션 안의 연결이 READ COMMITTED인지 확인한다
 * (DB 기본값에 기대는 것이 아니라 커넥션 풀 설정이 적용됨을 보인다). Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ConnectionIsolationIntegrationTest {

    private static final String SCHEMA = "wallet_service";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA)
            .withCommand("postgres", "-c", "default_transaction_isolation=serializable");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 쓰기_트랜잭션의_연결은_DB_기본값과_관계없이_READ_COMMITTED() {
        // 풀이 세션 기본값을 바꿔도 reset_val은 서버 기본값(SERIALIZABLE)으로 남는다
        assertEquals("serializable", jdbcTemplate.queryForObject(
                "SELECT reset_val FROM pg_settings WHERE name = 'default_transaction_isolation'", String.class));

        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertEquals("read committed", template.execute(status -> currentIsolation()));
    }

    @Test
    void 조회_전용_트랜잭션의_연결도_READ_COMMITTED() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);

        assertEquals("read committed", template.execute(status -> currentIsolation()));
    }

    private String currentIsolation() {
        return jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
    }
}

package com.travelpay.ledger.infra.persistence;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 원장 트랜잭션은 격리 수준을 @Transactional에 적지 않고 spring.datasource.hikari.transaction-isolation으로 정한다.
 * 서버 기본값을 SERIALIZABLE로 띄운 Postgres에서도 트랜잭션 안의 연결이 READ COMMITTED인지 DB에 직접 물어 확인한다
 * (DB 기본값에 기대는 것이 아니라 커넥션 풀 설정이 적용됨을 보인다). Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service"
})
class ConnectionIsolationIntegrationTest {

    private static final String SCHEMA = "ledger_service";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA)
            .withCommand("postgres", "-c", "default_transaction_isolation=serializable");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 서버_기본_격리_수준은_SERIALIZABLE() {
        // 풀이 세션 값을 바꿔도 reset_val은 서버 기본값으로 남는다
        assertEquals("serializable", jdbcTemplate.queryForObject(
                "SELECT reset_val FROM pg_settings WHERE name = 'default_transaction_isolation'", String.class));
    }

    @Test
    void 쓰기_트랜잭션의_연결은_서버_기본값과_관계없이_READ_COMMITTED() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        String[] result = template.execute(status -> new String[]{currentIsolation(), currentReadOnly()});

        assertEquals("read committed", result[0]);
        assertEquals("off", result[1]);
    }

    @Test
    void 조회_전용_트랜잭션의_연결도_READ_COMMITTED() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);

        String[] result = template.execute(status -> new String[]{currentIsolation(), currentReadOnly()});

        assertEquals("read committed", result[0]);
        // 조회 전용 트랜잭션에 묶인 연결에서 실행했음을 보인다
        assertEquals("on", result[1]);
    }

    /** 트랜잭션에 묶인 같은 연결에서 DB에 직접 묻는다 */
    private String currentIsolation() {
        return jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
    }

    private String currentReadOnly() {
        return jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class);
    }
}

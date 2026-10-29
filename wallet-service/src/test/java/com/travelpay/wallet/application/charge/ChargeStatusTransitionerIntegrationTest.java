package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres에 Flyway 마이그레이션을 적용하고 ddl-auto: validate로 띄운 앱에서 조건부 상태 전이를 검증한다.
 * 충전은 접수 단계(ChargeTransactionSteps.accept)로 만들어 REQUESTED 이력 1행에서 시작한다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "eureka.client.enabled=false")
class ChargeStatusTransitionerIntegrationTest {

    private static final String SCHEMA = "wallet_service";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    /** 운영과 같이 접속 URL의 currentSchema로 스키마를 정한다. 계좌번호 키는 테스트 전용 값 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private ChargeStatusTransitioner transitioner;

    @Autowired
    private ChargeTransactionSteps steps;

    @Autowired
    private AccountNumberCipher cipher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID chargeId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets");
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        chargeId = steps.accept(ChargeRequest.create(userId, walletId, 10_000, Currency.KRW,
                BankAccount.encrypt("004", "1234567890", cipher), "홍길동", UUID.randomUUID())).getId();
    }

    @Test
    void 현재_상태가_from이면_전이하고_이력을_남긴다() {
        boolean transitioned = inTransaction(() -> transitioner.transition(chargeId,
                ChargeStatus.REQUESTED, ChargeStatus.BANK_PROCESSING, null, null));

        assertTrue(transitioned);
        assertEquals("BANK_PROCESSING", chargeRow().get("status"));
        assertEquals(2, historyCount());
        Map<String, Object> last = history(ChargeStatus.BANK_PROCESSING);
        assertEquals("REQUESTED", last.get("from_status"));
        assertEquals("BANK_PROCESSING", last.get("to_status"));
    }

    @Test
    void 현재_상태가_from이_아니면_바꾸지_않고_이력도_남기지_않는다() {
        Map<String, Object> before = chargeRow();

        boolean transitioned = inTransaction(() -> transitioner.transition(chargeId,
                ChargeStatus.BANK_PROCESSING, ChargeStatus.CREDIT_PROCESSING, null, null));

        assertFalse(transitioned);
        Map<String, Object> after = chargeRow();
        assertEquals("REQUESTED", after.get("status"));
        assertEquals(before.get("updated_at"), after.get("updated_at"));
        assertEquals(1, historyCount());
    }

    @Test
    void 실패_코드_없이_전이해도_기존_실패_코드와_오류는_유지한다() {
        inTransaction(() -> transitioner.transition(chargeId, ChargeStatus.REQUESTED, ChargeStatus.FAILED,
                ChargeFailureCode.BANK_INSUFFICIENT_BALANCE, "bank rejected"));

        boolean transitioned = inTransaction(() -> transitioner.transition(chargeId,
                ChargeStatus.FAILED, ChargeStatus.MANUAL_REVIEW, null, null));

        assertTrue(transitioned);
        Map<String, Object> row = chargeRow();
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("BANK_INSUFFICIENT_BALANCE", row.get("failure_code"));
        assertEquals("bank rejected", row.get("last_error"));
        assertNull(history(ChargeStatus.MANUAL_REVIEW).get("reason_code"));
        assertEquals(3, historyCount());
    }

    @Test
    void 호출자_트랜잭션이_없으면_실행하지_않는다() {
        assertThrows(IllegalTransactionStateException.class, () -> transitioner.transition(chargeId,
                ChargeStatus.REQUESTED, ChargeStatus.BANK_PROCESSING, null, null));
        assertEquals("REQUESTED", chargeRow().get("status"));
    }

    private boolean inTransaction(java.util.function.Supplier<Boolean> call) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> call.get()));
    }

    private Map<String, Object> chargeRow() {
        return jdbcTemplate.queryForMap(
                "SELECT status, failure_code, last_error, updated_at FROM charge_requests WHERE id = ?", chargeId);
    }

    private int historyCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM charge_status_history WHERE charge_id = ?", Integer.class, chargeId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> history(ChargeStatus toStatus) {
        return jdbcTemplate.queryForMap("""
                SELECT from_status, to_status, reason_code FROM charge_status_history
                 WHERE charge_id = ? AND to_status = ?
                """, chargeId, toStatus.name());
    }
}

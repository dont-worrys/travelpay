package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.application.charge.dto.BankResult;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 실제 Postgres(Flyway 적용)에서 충전 복구 스케줄러를 검증한다. 은행·원장 클라이언트는 테스트 대역이고,
 * 스케줄 등록은 끄고 스케줄러를 직접 만들어 recover()를 호출한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeRecoverySchedulerIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final long AMOUNT = 10_000;
    private static final String TIMEOUT = "feign.RetryableException";

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
    private BankClient bankClient;

    @MockitoBean
    private LedgerTransactionClient ledgerClient;

    @MockitoSpyBean
    private ChargeSagaExecutor executor;

    @Autowired
    private ChargeRecoverySteps recoverySteps;

    @Autowired
    private ChargeTransactionSteps steps;

    @Autowired
    private AccountNumberCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChargeRecoveryScheduler scheduler;
    private UUID userId;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets");
        userId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        scheduler = new ChargeRecoveryScheduler(recoverySteps, executor, 10, "10s");
    }

    @Test
    void 대기_시간이_지난_BANK_PROCESSING은_재실행되어_COMPLETED가_된다() {
        UUID chargeId = newCharge(ChargeStatus.BANK_PROCESSING, 0, 31);
        givenWithdraw(new BankResult.Succeeded(UUID.randomUUID()));
        givenLedger(new LedgerPostResult.Posted(UUID.randomUUID()));

        scheduler.recover();

        assertEquals("COMPLETED", status(chargeId));
        Integer traced = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM charge_status_history
                 WHERE charge_id = ? AND trace_id LIKE 'recovery-%'
                """, Integer.class, chargeId);
        assertEquals(2, traced);
    }

    @Test
    void 대기_시간이_지나지_않은_건은_조회되지_않는다() {
        newCharge(ChargeStatus.BANK_PROCESSING, 0, 10);

        assertTrue(recoverySteps.findTargets(10).isEmpty());
    }

    @Test
    void 대기_시간은_attempt_count에_따라_30초부터_두_배씩_늘고_960초에서_멈춘다() {
        int[][] cases = {{0, 30}, {1, 30}, {2, 60}, {3, 120}, {6, 960}, {9, 960}};
        List<UUID> due = new ArrayList<>();
        List<UUID> notDue = new ArrayList<>();
        for (int[] c : cases) {
            due.add(newCharge(ChargeStatus.BANK_PROCESSING, c[0], c[1] + 2));
            notDue.add(newCharge(ChargeStatus.BANK_PROCESSING, c[0], c[1] - 2));
        }

        List<UUID> found = recoverySteps.findTargets(100).stream().map(ChargeRecoveryTarget::id).toList();

        assertTrue(found.containsAll(due), "대기 시간이 지난 건이 모두 조회되어야 한다");
        assertTrue(notDue.stream().noneMatch(found::contains), "대기 시간이 남은 건은 조회되지 않아야 한다");
    }

    @Test
    void 조회한_updated_at을_그대로_넘기면_선점된다() {
        UUID chargeId = newCharge(ChargeStatus.BANK_PROCESSING, 0, 3600);
        Instant stored = jdbcTemplate.queryForObject("SELECT updated_at FROM charge_requests WHERE id = ?",
                java.sql.Timestamp.class, chargeId).toInstant();

        ChargeRecoveryTarget target = recoverySteps.findTargets(10).getFirst();

        assertEquals(chargeId, target.id());
        assertEquals(stored, target.updatedAt());
        assertTrue(target.updatedAt().getNano() % 1_000 == 0, "DB 값은 마이크로초 정밀도");
        assertTrue(recoverySteps.claim(target));
        assertFalse(recoverySteps.claim(target), "선점 후에는 updated_at이 바뀌어 다시 선점되지 않는다");
    }

    @Test
    void 같은_건을_두_스레드가_동시에_선점하면_하나만_성공한다() throws Exception {
        newCharge(ChargeStatus.BANK_PROCESSING, 0, 3600);
        ChargeRecoveryTarget target = recoverySteps.findTargets(10).getFirst();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return recoverySteps.claim(target);
                }));
            }
            int claimed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) {
                    claimed++;
                }
            }
            assertEquals(1, claimed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 실행기는_활성_트랜잭션_없이_호출된다() {
        newCharge(ChargeStatus.BANK_PROCESSING, 0, 3600);
        AtomicBoolean called = new AtomicBoolean();
        AtomicBoolean txActive = new AtomicBoolean(true);
        doAnswer(invocation -> {
            called.set(true);
            txActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(executor).execute(any(UUID.class), anyString());

        scheduler.recover();

        assertTrue(called.get());
        assertFalse(txActive.get());
    }

    @Test
    void 한_건에서_예외가_나도_같은_배치의_다른_건은_처리된다() {
        UUID broken = newCharge(ChargeStatus.BANK_PROCESSING, 0, 7200);
        UUID healthy = newCharge(ChargeStatus.BANK_PROCESSING, 0, 3600);
        doThrow(new IllegalStateException("boom")).when(executor).execute(eq(broken), anyString());
        givenWithdraw(new BankResult.Succeeded(UUID.randomUUID()));
        givenLedger(new LedgerPostResult.Posted(UUID.randomUUID()));

        scheduler.recover();

        assertEquals("BANK_PROCESSING", status(broken));
        assertEquals("COMPLETED", status(healthy));
    }

    @Test
    void 결과_모름이_계속되면_다섯_번째_복구에서_MANUAL_REVIEW가_된다() {
        UUID chargeId = newCharge(ChargeStatus.BANK_PROCESSING, 0, 3600);
        givenWithdraw(new BankResult.Unknown(TIMEOUT));

        for (int i = 1; i <= 4; i++) {
            scheduler.recover();
            assertEquals("BANK_PROCESSING", status(chargeId));
            assertEquals(i, attemptCount(chargeId));
            pushBack(chargeId, 3600);
        }
        scheduler.recover();

        assertEquals("MANUAL_REVIEW", status(chargeId));
        assertEquals(5, attemptCount(chargeId));
        assertEquals("RETRY_EXHAUSTED", jdbcTemplate.queryForObject(
                "SELECT failure_code FROM charge_requests WHERE id = ?", String.class, chargeId));
        pushBack(chargeId, 3600);
        assertTrue(recoverySteps.findTargets(10).isEmpty());
    }

    @Test
    void 종료_상태는_조회되지_않는다() {
        for (ChargeStatus status : List.of(ChargeStatus.COMPLETED, ChargeStatus.FAILED, ChargeStatus.REFUNDED,
                ChargeStatus.MANUAL_REVIEW)) {
            newCharge(status, 0, 3600);
        }

        assertTrue(recoverySteps.findTargets(10).isEmpty());
    }

    /** 상태·attempt_count를 맞추고 updated_at을 DB now() 기준 secondsAgo초 전으로 둔 충전 */
    private UUID newCharge(ChargeStatus status, int attemptCount, int secondsAgo) {
        ChargeRequest charge = steps.accept(ChargeRequest.create(userId, walletId, AMOUNT, Currency.KRW,
                BankAccount.encrypt("004", "1234567890", cipher), "홍길동", UUID.randomUUID()));
        jdbcTemplate.update("""
                UPDATE charge_requests
                   SET status = ?, attempt_count = ?, updated_at = now() - make_interval(secs => ?)
                 WHERE id = ?
                """, status.name(), attemptCount, secondsAgo, charge.getId());
        return charge.getId();
    }

    private void pushBack(UUID chargeId, int secondsAgo) {
        jdbcTemplate.update("UPDATE charge_requests SET updated_at = now() - make_interval(secs => ?) WHERE id = ?",
                secondsAgo, chargeId);
    }

    private String status(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT status FROM charge_requests WHERE id = ?", String.class, chargeId);
    }

    private int attemptCount(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT attempt_count FROM charge_requests WHERE id = ?",
                Integer.class, chargeId);
    }

    private void givenWithdraw(BankResult result) {
        when(bankClient.withdraw(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(result);
    }

    private void givenLedger(LedgerPostResult result) {
        when(ledgerClient.postCharge(any(), any(), any(), anyLong())).thenReturn(result);
    }
}

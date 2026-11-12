package com.travelpay.ledger;

import com.travelpay.ledger.LedgerTestClient.Call;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.travelpay.ledger.LedgerTestClient.describe;
import static com.travelpay.ledger.LedgerTestClient.transferBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 같은 두 지갑 사이의 반대 방향 송금(A→B, B→A)을 동시에 보내도 교착 없이 모두 반영되는지 확인한다.
 * 원장은 거래마다 계정을 id 순으로 잠근다 (LedgerAccountJpaRepository: order by a.id, FOR NO KEY UPDATE).
 * <ul>
 *     <li>확정 단계: 정렬상 앞 계정 X를 풀 밖 연결이 잠근 채 두 요청을 보내, 둘 다 대기에 들어간 상태에서 뒤 계정 Y를
 *         FOR NO KEY UPDATE NOWAIT로 잡아 본다. 잡히면 두 요청 모두 Y를 아직 잠그지 않은 것(앞 계정부터 잠금)이다</li>
 *     <li>반복 단계: M회 동시에 보내 교착 증가량 0, POSTED 2 × M건, transactionId 중복 없음, 잔액 기대값·합계 보존을 단언한다.
 *         잠금 대기 샘플 수는 50ms 샘플링이라 짧은 대기를 놓칠 수 있어 기록만 한다</li>
 * </ul>
 * X·Y의 앞뒤는 DB의 id 정렬(ORDER BY id)로 정한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "spring.datasource.hikari.data-source-properties.ApplicationName=" + LedgerLockProbe.APP_NAME
})
class LedgerOppositeTransferIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final long INITIAL_BALANCE = 100_000;
    private static final int REPEAT = 50;
    private static final long A_TO_B = 300;
    private static final long B_TO_A = 170;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);
    /** 누적 통계는 백엔드가 쉬는 동안 최소 1초 간격으로 반영되므로 읽기 전에 기다린다 */
    private static final Duration STATS_FLUSH_WAIT = Duration.ofMillis(1_500);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private LedgerTestClient client;
    private String testName;
    private UUID walletA;
    private UUID walletB;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        client = new LedgerTestClient(port, jsonMapper);
        testName = testInfo.getDisplayName();
        walletA = client.openWallet();
        walletB = client.openWallet();
        client.charge(walletA, INITIAL_BALANCE);
        client.charge(walletB, INITIAL_BALANCE);
    }

    @Test
    void 확정_단계_두_요청은_정렬상_앞_계정에서_기다리고_뒤_계정은_잠그지_않는다(CapturedOutput output) throws Exception {
        List<UUID> ordered = accountsInIdOrder(accountId(walletA), accountId(walletB));
        UUID first = ordered.get(0);
        UUID second = ordered.get(1);
        UUID aToB = UUID.randomUUID();
        UUID bToA = UUID.randomUUID();
        List<Map<String, Object>> bodies = List.of(transferBody(aToB, walletA, walletB, A_TO_B),
                transferBody(bToA, walletB, walletA, B_TO_A));

        List<Call> calls;
        try (LedgerLockProbe probe = new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Connection holder = probe.holdAccount(first);
            probe.startSampling();
            LedgerTestClient.Pending pending = client.sendConcurrently(bodies);
            try {
                List<LedgerLockProbe.Sample> waiting = probe.awaitWaiting(2, WAIT_TIMEOUT);
                tryLockSecondWithNoWait(probe, second, waiting);
                System.out.println(probe.releaseInTime(holder, waiting).describe(testName));
            } finally {
                probe.release(holder);
                calls = pending.await(RESPONSE_TIMEOUT);
            }
            String context = describe(calls) + "\n" + probe.describeSamples() + "\n"
                    + LedgerSameReferenceIntegrationTest.appLog(output);
            for (Call call : calls) {
                assertEquals(201, call.status(), context);
            }
        }
        assertEquals("POSTED", status(aToB));
        assertEquals("POSTED", status(bToA));
        assertEquals(INITIAL_BALANCE - A_TO_B + B_TO_A, balance(walletA));
        assertEquals(INITIAL_BALANCE + A_TO_B - B_TO_A, balance(walletB));
    }

    @Test
    void 반복_단계_반대_방향_동시_송금은_교착_없이_모두_반영된다(CapturedOutput output) throws Exception {
        long deadlocksBefore = deadlocks();
        Set<UUID> referenceIds = new HashSet<>();
        List<Call> failed = new ArrayList<>();
        Set<String> transactionIds = new HashSet<>();
        long lockWaitSamples;
        try (LedgerLockProbe probe = new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            probe.startSampling();
            for (int round = 0; round < REPEAT; round++) {
                UUID aToB = UUID.randomUUID();
                UUID bToA = UUID.randomUUID();
                referenceIds.add(aToB);
                referenceIds.add(bToA);
                List<Call> calls = client.sendConcurrently(List.of(transferBody(aToB, walletA, walletB, A_TO_B),
                        transferBody(bToA, walletB, walletA, B_TO_A))).await(RESPONSE_TIMEOUT);
                for (Call call : calls) {
                    if (call.status() == 201) {
                        transactionIds.add(client.data(call.body()).get("transactionId").asString());
                    } else {
                        failed.add(call);
                    }
                }
            }
            lockWaitSamples = probe.lockWaitSampleCount();
        }
        Thread.sleep(STATS_FLUSH_WAIT.toMillis());
        long deadlockIncrease = deadlocks() - deadlocksBefore;
        System.out.println("[opposite-transfer] " + testName + " rounds=" + REPEAT + " lockWaitSamples="
                + lockWaitSamples + " (50ms 샘플링, 기록만) deadlockIncrease=" + deadlockIncrease);

        String context = describe(failed) + "\n" + LedgerSameReferenceIntegrationTest.appLog(output);
        assertEquals(0, failed.size(), context);
        assertEquals(2 * REPEAT, postedCount(referenceIds), context);
        assertEquals(2 * REPEAT, transactionIds.size(), "transactionId 중복 없음\n" + context);
        assertEquals(0, deadlockIncrease, context);
        long expectedA = INITIAL_BALANCE - REPEAT * A_TO_B + REPEAT * B_TO_A;
        long expectedB = INITIAL_BALANCE + REPEAT * A_TO_B - REPEAT * B_TO_A;
        assertEquals(expectedA, balance(walletA));
        assertEquals(expectedB, balance(walletB));
        assertEquals(2 * INITIAL_BALANCE, balance(walletA) + balance(walletB), "두 잔액의 합 보존");
    }

    /** 두 요청이 앞 계정에서 기다리는 동안 뒤 계정을 NOWAIT로 잡아 본다. 잡히면 곧바로 롤백, 55P03이면 실패 */
    private void tryLockSecondWithNoWait(LedgerLockProbe probe, UUID second, List<LedgerLockProbe.Sample> waiting)
            throws SQLException {
        try (Connection connection = probe.open("ledger-test-nowait");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM ledger_accounts WHERE id = ? FOR NO KEY UPDATE NOWAIT")) {
            statement.setObject(1, second);
            try {
                statement.executeQuery().close();
                System.out.println("[opposite-transfer] " + testName + " NOWAIT on second account succeeded "
                        + "(waiting=" + waiting.size() + ")");
            } catch (SQLException e) {
                if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                    fail("뒤 계정 Y를 먼저 잡은 요청이 있음(55P03): 잠금 순서가 id 순이 아님\n" + probe.describeSamples());
                }
                throw e;
            } finally {
                connection.rollback();
            }
        }
    }

    private List<UUID> accountsInIdOrder(UUID one, UUID other) {
        return jdbcTemplate.queryForList("SELECT id FROM ledger_accounts WHERE id IN (?, ?) ORDER BY id",
                UUID.class, one, other);
    }

    private UUID accountId(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", UUID.class, walletId);
    }

    private long balance(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", Long.class, walletId);
    }

    private String status(UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transactions WHERE reference_type = 'TRANSFER' AND reference_id = ?",
                String.class, referenceId);
    }

    private int postedCount(Set<UUID> referenceIds) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ledger_transactions
                 WHERE reference_type = 'TRANSFER' AND status = 'POSTED' AND reference_id::text = ANY (?)
                """, Integer.class, (Object) referenceIds.stream().map(UUID::toString).toArray(String[]::new));
    }

    private long deadlocks() {
        return jdbcTemplate.queryForObject(
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
    }
}

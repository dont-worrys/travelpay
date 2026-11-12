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

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.travelpay.ledger.LedgerTestClient.chargeBody;
import static com.travelpay.ledger.LedgerTestClient.describe;
import static com.travelpay.ledger.LedgerTestClient.system;
import static com.travelpay.ledger.LedgerTestClient.transferBody;
import static com.travelpay.ledger.LedgerTestClient.user;
import static com.travelpay.ledger.LedgerTestClient.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 같은 reference 요청을 실제 Postgres·실제 HTTP로 확인한다.
 * <ul>
 *     <li>동시 요청: 풀 밖 연결이 계정 잠금을 쥔 채 N건을 보내, N건 모두 잠금 대기에 들어간 것을 pg_stat_activity로 확인한 뒤 푼다.
 *         그래야 모두 "기존 거래 없음"을 읽고 같은 reference INSERT까지 경합한다</li>
 *     <li>해제는 첫 대기 요청의 xact_start + 1초 안이어야 하고, 넘으면 타이밍 조건 미충족으로 실패한다 (트랜잭션 제한 3초에 걸린 결과와 섞이지 않게)</li>
 *     <li>모든 경우의 불변식: reference당 거래 행 1건, 잔액은 한 번만 변경</li>
 * </ul>
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "spring.datasource.hikari.data-source-properties.ApplicationName=" + LedgerLockProbe.APP_NAME
})
class LedgerSameReferenceIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final int CONCURRENT = 6;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

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

    @BeforeEach
    void setUp(TestInfo testInfo) {
        client = new LedgerTestClient(port, jsonMapper);
        testName = testInfo.getDisplayName();
    }

    // ---- 동시 요청 ----

    @Test
    void 같은_내용_동시_요청은_한_건만_201이고_나머지는_같은_거래로_200이다(CapturedOutput output) throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = chargeBody(referenceId, walletId, 1_000);

        Run run = runWhileHolding(accountId(walletId), Collections.nCopies(CONCURRENT, body));

        Supplier<String> context = run.context(output);
        assertEquals(1, run.count(201), context);
        assertEquals(CONCURRENT - 1, run.count(200), context);
        Set<String> transactionIds = run.calls().stream()
                .map(call -> client.data(call.body()).get("transactionId").asString())
                .collect(Collectors.toSet());
        assertEquals(1, transactionIds.size(), context);
        assertEquals(transactionIds.iterator().next(), transactionId("CHARGE", referenceId).toString());
        assertEquals(1, transactionRows("CHARGE", referenceId), context);
        assertEquals(2, entryRows("CHARGE", referenceId), context);
        assertEquals(1_000, balance(walletId), "잔액은 한 번만 바뀐다\n" + context.get());
    }

    @Test
    void 같은_내용_동시_요청이_거절되면_모두_같은_422이고_거절_기록은_한_건이다(CapturedOutput output) throws Exception {
        UUID from = client.openWallet();
        UUID to = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = transferBody(referenceId, from, to, 1_000);

        Run run = runWhileHolding(accountId(from), Collections.nCopies(CONCURRENT, body));

        Supplier<String> context = run.context(output);
        assertEquals(CONCURRENT, run.count(422), context);
        for (Call call : run.calls()) {
            assertEquals("LEDGER_REJECTED_INSUFFICIENT_BALANCE", client.errorCode(call.body()), context);
        }
        assertEquals(1, transactionRows("TRANSFER", referenceId), context);
        assertEquals("REJECTED", status("TRANSFER", referenceId), context);
        assertEquals(0, entryRows("TRANSFER", referenceId), context);
        assertEquals(0, balance(from), context);
        assertEquals(0, balance(to), context);
    }

    @Test
    void 다른_내용_동시_요청은_먼저_기록된_쪽만_성공하고_다른_내용은_409다(CapturedOutput output) throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> small = chargeBody(referenceId, walletId, 1_000);
        Map<String, Object> large = chargeBody(referenceId, walletId, 2_000);
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (int i = 0; i < CONCURRENT; i++) {
            bodies.add(i % 2 == 0 ? small : large);
        }

        Run run = runWhileHolding(accountId(walletId), bodies);

        Supplier<String> context = run.context(output);
        long winnerAmount = balance(walletId);
        assertTrue(winnerAmount == 1_000 || winnerAmount == 2_000, context);
        assertEquals(1, run.count(201), context);
        for (Call call : run.calls()) {
            long amount = call.index() % 2 == 0 ? 1_000 : 2_000;
            if (amount == winnerAmount) {
                assertTrue(call.status() == 201 || call.status() == 200, context);
            } else {
                assertEquals(409, call.status(), context);
                assertEquals("LEDGER_REFERENCE_CONFLICT", client.errorCode(call.body()), context);
            }
        }
        assertEquals(1, transactionRows("CHARGE", referenceId), context);
        assertEquals(2, entryRows("CHARGE", referenceId), context);
    }

    // ---- 순차 재요청 ----

    @Test
    void 금액이_다른_재요청은_409이고_기존_거래와_잔액은_그대로다() throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        assertStatus(201, client.register(chargeBody(referenceId, walletId, 1_000)));

        HttpResponse<String> conflict = client.register(chargeBody(referenceId, walletId, 2_000));

        assertConflict(conflict);
        assertEquals(1, transactionRows("CHARGE", referenceId));
        assertEquals(1_000, balance(walletId));
    }

    @Test
    void 항목의_지갑_유형_방향이_다른_재요청은_409다() throws Exception {
        UUID walletId = client.openWallet();
        UUID otherWalletId = client.openWallet();
        client.charge(walletId, 5_000);
        UUID referenceId = UUID.randomUUID();
        assertStatus(201, client.register(transferBody(referenceId, walletId, otherWalletId, 1_000)));
        UUID thirdWalletId = client.openWallet();

        assertConflict(client.register(transferBody(referenceId, walletId, thirdWalletId, 1_000)));
        assertConflict(client.register(LedgerTestClient.body("TRANSFER", referenceId, List.of(
                user(walletId, "KRW", "DEBIT", 1_000, "PAYMENT_OUT"),
                user(otherWalletId, "KRW", "CREDIT", 1_000, "PAYMENT_IN")))));
        assertConflict(client.register(transferBody(referenceId, otherWalletId, walletId, 1_000)));

        assertEquals(1, transactionRows("TRANSFER", referenceId));
        assertEquals(4_000, balance(walletId));
        assertEquals(1_000, balance(otherWalletId));
        assertEquals(0, balance(thirdWalletId));
    }

    @Test
    void 원거래_ID가_다른_재요청은_409다() throws Exception {
        UUID walletId = client.openWallet();
        UUID original = client.charge(walletId, 5_000);
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = with(chargeBody(referenceId, walletId, 1_000), "originalTransactionId",
                original.toString());
        assertStatus(201, client.register(body));

        assertConflict(client.register(with(body, "originalTransactionId", UUID.randomUUID().toString())));
        assertConflict(client.register(with(body, "originalTransactionId", null)));

        assertEquals(1, transactionRows("CHARGE", referenceId));
        assertEquals(6_000, balance(walletId));
    }

    @Test
    void userId만_다른_재요청은_200이고_저장된_userId는_첫_요청_값이다() throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        UUID firstUserId = UUID.randomUUID();
        Map<String, Object> body = with(chargeBody(referenceId, walletId, 1_000), "userId", firstUserId.toString());
        HttpResponse<String> created = client.register(body);
        assertStatus(201, created);

        HttpResponse<String> retried = client.register(with(body, "userId", UUID.randomUUID().toString()));

        assertStatus(200, retried);
        assertEquals(client.data(created).get("transactionId").asString(),
                client.data(retried).get("transactionId").asString());
        assertEquals(firstUserId, jdbcTemplate.queryForObject(
                "SELECT user_id FROM ledger_transactions WHERE reference_type = 'CHARGE' AND reference_id = ?",
                UUID.class, referenceId));
        assertEquals(1_000, balance(walletId));
    }

    @Test
    void 설명만_다르거나_항목_순서만_다른_재요청은_200이다() throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = with(chargeBody(referenceId, walletId, 1_000), "description", "처음");
        assertStatus(201, client.register(body));

        assertStatus(200, client.register(with(body, "description", "다른 설명")));
        assertStatus(200, client.register(with(LedgerTestClient.body("CHARGE", referenceId, List.of(
                user(walletId, "KRW", "CREDIT", 1_000, "CHARGE_IN"),
                system("CHARGE_SOURCE", "KRW", "DEBIT", 1_000, "CHARGE_IN"))), "description", "처음")));

        assertEquals("처음", jdbcTemplate.queryForObject(
                "SELECT description FROM ledger_transactions WHERE reference_type = 'CHARGE' AND reference_id = ?",
                String.class, referenceId));
        assertEquals(1, transactionRows("CHARGE", referenceId));
        assertEquals(1_000, balance(walletId));
    }

    @Test
    void 거절된_reference는_같은_내용이면_다시_422이고_다른_내용이면_409다() throws Exception {
        UUID from = client.openWallet();
        UUID to = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = transferBody(referenceId, from, to, 1_000);
        HttpResponse<String> rejected = client.register(body);
        assertStatus(422, rejected);
        // 거절 뒤 잔액이 생겨도 같은 내용 재요청은 기존 거절 결과
        client.charge(from, 5_000);

        HttpResponse<String> again = client.register(body);
        HttpResponse<String> conflict = client.register(transferBody(referenceId, from, to, 500));

        assertStatus(422, again);
        assertEquals("LEDGER_REJECTED_INSUFFICIENT_BALANCE", client.errorCode(again.body()));
        assertConflict(conflict);
        assertEquals(1, transactionRows("TRANSFER", referenceId));
        assertEquals("REJECTED", status("TRANSFER", referenceId));
        assertEquals(5_000, balance(from));
        assertEquals(0, balance(to));
    }

    // ---- 실행·확인 도구 ----

    /** accountId 잠금을 쥔 채 bodies를 동시에 보내고, 모두 잠금 대기에 들어가면 제한 시간 안에 풀고 응답을 모은다 */
    private Run runWhileHolding(UUID accountId, List<Map<String, Object>> bodies) throws Exception {
        try (LedgerLockProbe probe = new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Connection holder = probe.holdAccount(accountId);
            probe.startSampling();
            LedgerTestClient.Pending pending = client.sendConcurrently(bodies);
            List<Call> calls;
            try {
                List<LedgerLockProbe.Sample> waiting = probe.awaitWaiting(bodies.size(), WAIT_TIMEOUT);
                System.out.println(probe.releaseInTime(holder, waiting).describe(testName));
            } finally {
                // 대기 확인·해제에 실패해도 요청이 끝나도록 풀고 응답을 모은다 (이미 풀었으면 아무 일 없음)
                probe.release(holder);
                calls = pending.await(RESPONSE_TIMEOUT);
            }
            return new Run(calls, probe.describeSamples());
        }
    }

    private record Run(List<Call> calls, String samples) {

        long count(int status) {
            return calls.stream().filter(call -> call.status() == status).count();
        }

        Supplier<String> context(CapturedOutput output) {
            return () -> describe(calls) + "\n" + samples + "\n" + appLog(output);
        }
    }

    /** 컨트롤러 WARN(exception=)·DB 처리기 ERROR·Hibernate SQL 예외(org.hibernate.orm.jdbc.error) 줄 */
    static String appLog(CapturedOutput output) {
        return output.getAll().lines()
                .filter(line -> line.contains("원장 거래 처리 실패") || line.contains("Database unavailable")
                        || line.contains("\"level\":\"ERROR\"") || line.contains("org.hibernate.orm.jdbc.error"))
                .collect(Collectors.joining("\n", "앱 로그:\n", ""));
    }

    private void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response.body());
    }

    private void assertConflict(HttpResponse<String> response) {
        assertStatus(409, response);
        assertEquals("LEDGER_REFERENCE_CONFLICT", client.errorCode(response.body()));
    }

    private UUID accountId(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", UUID.class, walletId);
    }

    private long balance(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", Long.class, walletId);
    }

    private int transactionRows(String referenceType, UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference_type = ? AND reference_id = ?",
                Integer.class, referenceType, referenceId);
    }

    private UUID transactionId(String referenceType, UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_transactions WHERE reference_type = ? AND reference_id = ?",
                UUID.class, referenceType, referenceId);
    }

    private String status(String referenceType, UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transactions WHERE reference_type = ? AND reference_id = ?",
                String.class, referenceType, referenceId);
    }

    private int entryRows(String referenceType, UUID referenceId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.reference_type = ? AND t.reference_id = ?
                """, Integer.class, referenceType, referenceId);
    }
}

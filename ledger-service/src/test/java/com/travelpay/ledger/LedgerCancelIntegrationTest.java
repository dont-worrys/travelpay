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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.travelpay.ledger.LedgerTestClient.cancelBody;
import static com.travelpay.ledger.LedgerTestClient.chargeBody;
import static com.travelpay.ledger.LedgerTestClient.describe;
import static com.travelpay.ledger.LedgerTestClient.transferBody;
import static com.travelpay.ledger.LedgerTestClient.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 거래 취소 API(POST /internal/v1/ledger/transactions/cancel)를 실제 Postgres·실제 HTTP로 확인한다.
 * <ul>
 *     <li>거래가 없으면 REJECTED(CANCELLED_BY_REQUESTER)를 기록하고 201, 이미 있으면 바꾸지 않고 200으로 그대로 돌려준다</li>
 *     <li>취소 뒤 같은 reference의 등록은 기존 규칙대로 409이고 잔액·취소 기록이 그대로다</li>
 *     <li>등록이 계정 잠금을 기다리는 동안 취소가 먼저 커밋되면 거래는 취소 하나만 남는다
 *         (풀 밖 연결이 계정을 잠근 채 등록을 보내고, 대기에 들어간 것을 pg_stat_activity로 확인한 뒤 취소하고 푼다)</li>
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
class LedgerCancelIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final String CANCELLED = "CANCELLED_BY_REQUESTER";
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

    // ---- 거래가 없을 때 ----

    @Test
    void 거래가_없으면_취소를_기록하고_201이다() throws Exception {
        UUID referenceId = UUID.randomUUID();

        HttpResponse<String> response = client.cancel(cancelBody("PAYMENT", referenceId));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode data = client.data(response);
        assertEquals("REJECTED", data.get("status").asString());
        assertEquals(CANCELLED, data.get("rejectReason").asString());
        assertTrue(data.get("entries").isEmpty(), response.body());
        assertEquals(transactionId("PAYMENT", referenceId).toString(), data.get("transactionId").asString());
        assertEquals(1, transactionRows("PAYMENT", referenceId));
        assertEquals(CANCELLED, rejectReason("PAYMENT", referenceId));
        assertEquals(0, entryRows("PAYMENT", referenceId));
    }

    @Test
    void 같은_취소를_반복해도_거래는_하나이고_같은_거래를_200으로_돌려준다() throws Exception {
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = cancelBody("SETTLEMENT", referenceId);

        HttpResponse<String> first = client.cancel(body);
        HttpResponse<String> second = client.cancel(body);
        HttpResponse<String> third = client.cancel(with(body, "description", "다시 보낸 취소"));

        assertEquals(201, first.statusCode(), first.body());
        assertEquals(200, second.statusCode(), second.body());
        assertEquals(200, third.statusCode(), third.body());
        String transactionId = client.data(first).get("transactionId").asString();
        assertEquals(transactionId, client.data(second).get("transactionId").asString());
        assertEquals(transactionId, client.data(third).get("transactionId").asString());
        assertEquals(CANCELLED, client.data(third).get("rejectReason").asString());
        assertEquals(1, transactionRows("SETTLEMENT", referenceId));
    }

    @Test
    void 취소_뒤_같은_reference로_등록하면_409이고_잔액과_취소_기록은_그대로다() throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        assertEquals(201, client.cancel(cancelBody("CHARGE", referenceId)).statusCode());

        HttpResponse<String> register = client.register(chargeBody(referenceId, walletId, 1_000));

        assertEquals(409, register.statusCode(), register.body());
        assertEquals("LEDGER_REFERENCE_CONFLICT", client.errorCode(register.body()));
        assertEquals(0, balance(walletId));
        assertEquals(1, transactionRows("CHARGE", referenceId));
        assertEquals("REJECTED", status("CHARGE", referenceId));
        assertEquals(CANCELLED, rejectReason("CHARGE", referenceId));
        assertEquals(0, entryRows("CHARGE", referenceId));
    }

    // ---- 이미 거래가 있을 때 ----

    @Test
    void 이미_POSTED인_거래는_바뀌지_않고_항목과_함께_200으로_돌려준다() throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        HttpResponse<String> registered = client.register(chargeBody(referenceId, walletId, 1_000));
        assertEquals(201, registered.statusCode(), registered.body());

        HttpResponse<String> response = client.cancel(cancelBody("CHARGE", referenceId));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = client.data(response);
        assertEquals(client.data(registered).get("transactionId").asString(), data.get("transactionId").asString());
        assertEquals("POSTED", data.get("status").asString());
        assertTrue(data.get("rejectReason").isNull(), response.body());
        assertEquals(2, data.get("entries").size(), response.body());
        assertEquals(1_000, balance(walletId));
        assertEquals(1, transactionRows("CHARGE", referenceId));
        assertEquals("POSTED", status("CHARGE", referenceId));
        assertEquals(2, entryRows("CHARGE", referenceId));
    }

    @Test
    void 이미_REJECTED인_거래는_바뀌지_않고_원래_거절_사유로_200을_돌려준다() throws Exception {
        UUID from = client.openWallet();
        UUID to = client.openWallet();
        UUID referenceId = UUID.randomUUID();
        HttpResponse<String> rejected = client.register(transferBody(referenceId, from, to, 1_000));
        assertEquals(422, rejected.statusCode(), rejected.body());

        HttpResponse<String> response = client.cancel(cancelBody("TRANSFER", referenceId));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = client.data(response);
        assertEquals(transactionId("TRANSFER", referenceId).toString(), data.get("transactionId").asString());
        assertEquals("REJECTED", data.get("status").asString());
        assertEquals("INSUFFICIENT_BALANCE", data.get("rejectReason").asString());
        assertEquals(1, transactionRows("TRANSFER", referenceId));
        assertEquals("INSUFFICIENT_BALANCE", rejectReason("TRANSFER", referenceId));
    }

    // ---- 등록과 겹칠 때 ----

    @Test
    void 등록이_계정_잠금을_기다리는_중_취소하면_거래는_취소_하나만_남는다(CapturedOutput output) throws Exception {
        UUID walletId = client.openWallet();
        UUID referenceId = UUID.randomUUID();

        HttpResponse<String> cancel;
        List<Call> calls;
        String samples;
        try (LedgerLockProbe probe = new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Connection holder = probe.holdAccount(accountId(walletId));
            probe.startSampling();
            LedgerTestClient.Pending pending = client.sendConcurrently(
                    List.of(chargeBody(referenceId, walletId, 1_000)));
            try {
                List<LedgerLockProbe.Sample> waiting = probe.awaitWaiting(1, WAIT_TIMEOUT);
                // 취소는 계정을 잠그지 않으므로 등록이 잠금을 기다리는 동안 먼저 커밋된다
                cancel = client.cancel(cancelBody("CHARGE", referenceId));
                System.out.println(probe.releaseInTime(holder, waiting).describe(testName));
            } finally {
                // 대기 확인·해제에 실패해도 요청이 끝나도록 풀고 응답을 모은다 (이미 풀었으면 아무 일 없음)
                probe.release(holder);
                calls = pending.await(RESPONSE_TIMEOUT);
            }
            samples = probe.describeSamples();
        }

        Supplier<String> context = () -> describe(calls) + "\n" + samples + "\n"
                + LedgerSameReferenceIntegrationTest.appLog(output);
        assertEquals(201, cancel.statusCode(), cancel.body());
        assertEquals(CANCELLED, client.data(cancel).get("rejectReason").asString(), context);
        Call register = calls.get(0);
        assertEquals(409, register.status(), context);
        assertEquals("LEDGER_REFERENCE_CONFLICT", client.errorCode(register.body()), context);
        assertEquals(1, transactionRows("CHARGE", referenceId), context);
        assertEquals("REJECTED", status("CHARGE", referenceId), context);
        assertEquals(CANCELLED, rejectReason("CHARGE", referenceId), context);
        assertEquals(0, entryRows("CHARGE", referenceId), context);
        assertEquals(0, balance(walletId), "잔액은 바뀌지 않는다\n" + context.get());
    }

    // 등록이 거래 행을 INSERT했지만 아직 커밋하지 않은 때 취소가 들어오면, 취소 INSERT가 reference UNIQUE에서 기다린다.
    // 등록이 커밋하면 취소는 그 거래를 200으로 돌려주고 취소 기록은 남지 않는다
    @Test
    void 등록이_INSERT_뒤_커밋_전일_때_취소하고_등록이_커밋되면_그_거래를_200으로_돌려준다() throws Exception {
        UUID referenceId = UUID.randomUUID();

        HeldCancel held = cancelWhileRowHeld("PAYMENT", referenceId, Connection::commit);

        assertEquals(200, held.call().status(), held.context());
        JsonNode data = client.data(held.call().body());
        assertEquals(held.heldId().toString(), data.get("transactionId").asString(), held.context());
        assertEquals("POSTED", data.get("status").asString(), held.context());
        assertEquals(1, transactionRows("PAYMENT", referenceId));
        assertEquals("POSTED", status("PAYMENT", referenceId));
    }

    // 같은 상황에서 등록이 롤백되면(잔액 부족·시간 초과 등) 취소는 기다림이 풀린 뒤 저장되어 201
    @Test
    void 등록이_INSERT_뒤_커밋_전일_때_취소하고_등록이_롤백되면_취소를_기록하고_201이다() throws Exception {
        UUID referenceId = UUID.randomUUID();

        HeldCancel held = cancelWhileRowHeld("PAYMENT", referenceId, Connection::rollback);

        assertEquals(201, held.call().status(), held.context());
        assertEquals(CANCELLED, client.data(held.call().body()).get("rejectReason").asString(), held.context());
        assertEquals(1, transactionRows("PAYMENT", referenceId));
        assertEquals(CANCELLED, rejectReason("PAYMENT", referenceId));
    }

    @Test
    void 같은_reference로_취소가_동시에_와도_거래는_하나이고_한_건만_201이다() throws Exception {
        UUID referenceId = UUID.randomUUID();
        List<Map<String, Object>> bodies = Collections.nCopies(6, cancelBody("SETTLEMENT", referenceId));

        List<Call> calls = client.sendConcurrently(LedgerTestClient.CANCEL, bodies).await(RESPONSE_TIMEOUT);

        String context = describe(calls);
        assertEquals(1, calls.stream().filter(call -> call.status() == 201).count(), context);
        assertEquals(5, calls.stream().filter(call -> call.status() == 200).count(), context);
        Set<String> transactionIds = calls.stream()
                .map(call -> client.data(call.body()).get("transactionId").asString())
                .collect(Collectors.toSet());
        assertEquals(Set.of(transactionId("SETTLEMENT", referenceId).toString()), transactionIds, context);
        assertEquals(1, transactionRows("SETTLEMENT", referenceId));
        assertEquals(CANCELLED, rejectReason("SETTLEMENT", referenceId));
    }

    // ---- 형식 오류 ----

    @Test
    void referenceId가_없으면_400이고_아무것도_기록하지_않는다() throws Exception {
        long before = allTransactionRows();

        HttpResponse<String> response = client.post(LedgerTestClient.CANCEL, "{\"referenceType\":\"PAYMENT\"}");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(before, allTransactionRows());
    }

    // ---- 커밋 전 행을 쥔 채 취소 ----

    private interface Finish {
        void apply(Connection connection) throws SQLException;
    }

    private record HeldCancel(UUID heldId, Call call, String context) {
    }

    /**
     * 앱 풀 밖 연결로 같은 reference의 POSTED 행을 INSERT만 하고 쥔 채(등록이 INSERT까지 한 상태) 취소를 보낸다.
     * 취소가 UNIQUE 잠금에서 기다리는 것을 pg_stat_activity로 확인한 뒤 finish(커밋 또는 롤백)로 푼다.
     * 취소 트랜잭션 제한(3초)에 걸리지 않도록 대기를 확인하자마자 푼다.
     */
    private HeldCancel cancelWhileRowHeld(String referenceType, UUID referenceId, Finish finish) throws Exception {
        try (LedgerLockProbe probe = new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Connection holder = probe.open(LedgerLockProbe.HOLDER_NAME);
            UUID heldId = UUID.randomUUID();
            try (PreparedStatement insert = holder.prepareStatement("""
                    INSERT INTO ledger_transactions (id, reference_type, reference_id, request_hash, status)
                    VALUES (?, ?, ?, ?, 'POSTED')
                    """)) {
                insert.setObject(1, heldId);
                insert.setString(2, referenceType);
                insert.setObject(3, referenceId);
                insert.setString(4, "0".repeat(64));
                insert.executeUpdate();
            }
            LedgerTestClient.Pending pending = client.sendConcurrently(LedgerTestClient.CANCEL,
                    List.of(cancelBody(referenceType, referenceId)));
            List<Call> calls;
            try {
                awaitAppWaitingOnLock(WAIT_TIMEOUT);
                finish.apply(holder);
            } finally {
                // 대기 확인에 실패해도 요청이 끝나도록 푼다(이미 끝났으면 아무 일 없음)
                holder.rollback();
                calls = pending.await(RESPONSE_TIMEOUT);
            }
            return new HeldCancel(heldId, calls.get(0), describe(calls));
        }
    }

    /** 앱 연결 중 하나가 행 잠금(여기서는 reference UNIQUE의 커밋 전 행)을 기다릴 때까지 본다 */
    private void awaitAppWaitingOnLock(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE application_name = ? AND wait_event_type = 'Lock'
                    """, Integer.class, LedgerLockProbe.APP_NAME);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("취소가 잠금 대기에 들어가지 않음 (" + timeout.toMillis() + "ms)");
    }

    // ---- 준비·조회 ----

    private UUID accountId(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", UUID.class, walletId);
    }

    private long balance(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", Long.class, walletId);
    }

    private long allTransactionRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class);
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

    private String rejectReason(String referenceType, UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM ledger_transactions WHERE reference_type = ? AND reference_id = ?",
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

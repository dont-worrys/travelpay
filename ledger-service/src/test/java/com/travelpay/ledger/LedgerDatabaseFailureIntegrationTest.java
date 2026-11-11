package com.travelpay.ledger;

import com.travelpay.ledger.LedgerTestClient.Call;
import com.travelpay.ledger.application.transaction.LedgerTransactionService;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.travelpay.ledger.LedgerTestClient.chargeBody;
import static com.travelpay.ledger.LedgerTestClient.describe;
import static com.travelpay.ledger.LedgerTestClient.transferBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * DB 쪽 실패가 났을 때 원장 등록 API의 실제 응답과 실제 예외를 기록한다. 기록 위주이며 고정 단언은 아래뿐이다.
 * <ul>
 *     <li>5xx는 모두 503 (500 등이 나오면 실패로 두고 예외 클래스와 원인 연쇄를 메시지에 남긴다)</li>
 *     <li>실패한 요청은 거래 행 0건·잔액 불변, reference당 행 1건 이하, 최종 잔액 = 시작 잔액 + POSTED 순변동</li>
 * </ul>
 * 예외는 LedgerTransactionService.register를 감싼 스파이로 받는다 (그대로 다시 던지므로 응답은 바뀌지 않음).
 * 기록은 표준 출력의 "[db-failure]" 줄로 남긴다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "spring.datasource.hikari.data-source-properties.ApplicationName=" + LedgerLockProbe.APP_NAME
})
class LedgerDatabaseFailureIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    /** 잠금을 쥔 채 앱 응답을 기다리는 상한. 넘으면 기한이 걸리지 않은 것으로 보고 실패 */
    private static final Duration HOLD_LIMIT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);
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

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private LedgerTransactionService transactionService;

    /** referenceId → register가 던진 예외 */
    private final Map<UUID, Throwable> thrown = new ConcurrentHashMap<>();
    private LedgerTestClient client;
    private String testName;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        client = new LedgerTestClient(port, jsonMapper);
        testName = testInfo.getDisplayName();
        thrown.clear();
        doAnswer(invocation -> {
            RegisterTransactionCommand command = invocation.getArgument(0);
            try {
                return invocation.callRealMethod();
            } catch (Throwable t) {
                thrown.put(command.referenceId(), t);
                throw t;
            }
        }).when(transactionService).register(any());
    }

    // ---- 1. 잠금 대기 중 트랜잭션 제한 초과 ----

    @Test
    void 잠금_대기_중_트랜잭션_제한을_넘기면_503이고_롤백되며_해제_뒤_재시도는_201이다(CapturedOutput output)
            throws Exception {
        UUID walletId = client.openWallet();
        client.charge(walletId, 5_000);
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = chargeBody(referenceId, walletId, 1_000);

        Call call;
        LedgerLockProbe.Sample waiting;
        Instant respondedDb;
        String samples;
        try (LedgerLockProbe probe = newProbe()) {
            Connection holder = probe.holdAccount(accountId(walletId));
            probe.startSampling();
            LedgerTestClient.Pending pending = client.sendConcurrently(List.of(body));
            waiting = probe.awaitWaiting(1, WAIT_TIMEOUT).getFirst();
            // 앱 응답이 올 때까지 잠금을 유지한다
            try {
                call = pending.await(HOLD_LIMIT).getFirst();
            } catch (TimeoutException e) {
                fail("잠금을 " + HOLD_LIMIT.toSeconds() + "초 쥐어도 앱 응답이 없음: 트랜잭션 기한이 잠금 대기에 걸리지 않음\n"
                        + probe.describeSamples());
                return;
            }
            respondedDb = probe.dbNow();
            samples = probe.describeSamples();
            probe.release(holder);
        }
        Instant waitEntered = waiting.at();
        String chain = chain(thrown.get(referenceId));
        System.out.println("[db-failure] " + testName + " xact_start=" + waiting.xactStart()
                + " lockWaitDetected=" + waitEntered + " respondedDb=" + respondedDb
                + " xactStart->response=" + Duration.between(waiting.xactStart(), respondedDb).toMillis() + "ms"
                + " lockWait->response=" + Duration.between(waitEntered, respondedDb).toMillis() + "ms"
                + " clientElapsed=" + call.elapsedMs() + "ms status=" + call.status()
                + " code=" + client.errorCode(call.body()) + " exception=" + chain
                + " sqlErrors=" + sqlErrorLines(output));

        String context = call + "\n" + chain + "\n" + samples + "\n" + LedgerSameReferenceIntegrationTest.appLog(output);
        assertServiceUnavailableIf5xx(call, chain, context);
        assertEquals(503, call.status(), context);
        assertEquals(0, transactionRows("CHARGE", referenceId), context);
        assertEquals(5_000, balance(walletId), context);

        HttpResponse<String> retried = client.register(body);
        System.out.println("[db-failure] " + testName + " retryAfterRelease status=" + retried.statusCode());
        assertEquals(201, retried.statusCode(), retried.body());
        assertEquals(6_000, balance(walletId));
    }

    // ---- 2. 교착 ----

    @Test
    void 외부_연결과_교착이_나면_희생된_쪽은_롤백되고_결과를_기록한다(CapturedOutput output) throws Exception {
        UUID walletA = client.openWallet();
        UUID walletB = client.openWallet();
        client.charge(walletA, 10_000);
        client.charge(walletB, 10_000);
        List<UUID> ordered = jdbcTemplate.queryForList(
                "SELECT id FROM ledger_accounts WHERE id IN (?, ?) ORDER BY id", UUID.class,
                accountId(walletA), accountId(walletB));
        UUID first = ordered.get(0);
        UUID second = ordered.get(1);
        UUID referenceId = UUID.randomUUID();
        Map<String, Object> body = transferBody(referenceId, walletA, walletB, 1_000);
        String fetchConsistency = jdbcTemplate.queryForObject("SHOW stats_fetch_consistency", String.class);
        String deadlockTimeout = jdbcTemplate.queryForObject("SHOW deadlock_timeout", String.class);
        long deadlocksBefore = deadlocks();

        Call call;
        String external;
        String samples;
        Instant appWaitDetected;
        java.util.concurrent.atomic.AtomicReference<Instant> externalLockRequestedAt =
                new java.util.concurrent.atomic.AtomicReference<>();
        ExecutorService externalThread = Executors.newSingleThreadExecutor();
        try (LedgerLockProbe probe = newProbe()) {
            // 외부 연결이 뒤 계정 Y를 쥔다 → 앱은 앞 계정 X를 잠근 뒤 Y에서 기다린다
            Connection holder = probe.holdAccount(second);
            probe.startSampling();
            LedgerTestClient.Pending pending = client.sendConcurrently(List.of(body));
            appWaitDetected = probe.awaitWaiting(1, WAIT_TIMEOUT).getFirst().at();
            // 외부 연결이 X를 요청해 교착을 만든다. 앱이 먼저 기다렸으므로 deadlock_timeout이 먼저 지나는 쪽은 앱이다
            // (외부 세션의 deadlock_timeout을 따로 늘리지는 않았다. 희생자는 결과로 기록하고 경우별로 확인한다)
            Future<String> externalLock = externalThread.submit(() -> {
                externalLockRequestedAt.set(Instant.now());
                try (PreparedStatement statement = holder.prepareStatement(
                        "SELECT id FROM ledger_accounts WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, first);
                    statement.executeQuery().close();
                    return "acquired";
                } catch (SQLException e) {
                    return "SQLState=" + e.getSQLState() + " " + e.getClass().getName();
                }
            });
            call = pending.await(RESPONSE_TIMEOUT).getFirst();
            external = externalLock.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            samples = probe.describeSamples();
            probe.release(holder);
        } finally {
            externalThread.shutdownNow();
        }
        Thread.sleep(STATS_FLUSH_WAIT.toMillis());
        long deadlockIncrease = deadlocks() - deadlocksBefore;
        String chain = chain(thrown.get(referenceId));
        boolean appVictim = call.status() == 503 && chain.contains("SQLState=40P01");
        boolean externalVictim = external.contains("SQLState=40P01");
        String victim = appVictim ? "app" : externalVictim ? "external" : "none";
        String timeline = "appWaitDetected=" + appWaitDetected + " deadlockFormed(externalLockRequested)="
                + externalLockRequestedAt.get() + " appResponded=" + call.respondedAt();
        System.out.println("[db-failure] " + testName + " stats_fetch_consistency=" + fetchConsistency
                + " deadlock_timeout=" + deadlockTimeout + " victim=" + victim + " external=" + external
                + " status=" + call.status() + " code=" + client.errorCode(call.body()) + " clientElapsed="
                + call.elapsedMs() + "ms exception=" + chain + " deadlockIncrease=" + deadlockIncrease
                + " " + timeline + " sqlErrors=" + sqlErrorLines(output));

        String context = call + "\nexternal=" + external + "\n" + chain + "\n" + timeline + "\n" + samples + "\n"
                + LedgerSameReferenceIntegrationTest.appLog(output);
        assertServiceUnavailableIf5xx(call, chain, context);
        if (!appVictim && !externalVictim) {
            // 앱이 교착 전에 트랜잭션 제한(3초) 등 다른 이유로 끝난 경우
            fail("교착이 형성되기 전에 끝남(희생자 없음): " + timeline + "\n" + context);
        }
        // 교착이 실제로 일어났는지: 별도 트랜잭션, 통계 반영을 기다린 뒤 읽은 증가량
        assertTrue(deadlockIncrease >= 1, "deadlocks 증가량 ≥ 1\n" + context);
        if (appVictim) {
            assertEquals(0, transactionRows("TRANSFER", referenceId), context);
            assertEquals(10_000, balance(walletA), context);
            assertEquals(10_000, balance(walletB), context);
            HttpResponse<String> retried = client.register(body);
            System.out.println("[db-failure] " + testName + " retryAfterRelease status=" + retried.statusCode());
            assertEquals(201, retried.statusCode(), retried.body());
        } else {
            // 외부 연결이 희생됨: 외부 연결에서 40P01, 앱 요청은 그대로 반영된다
            assertEquals(201, call.status(), context);
            assertEquals(1, transactionRows("TRANSFER", referenceId), context);
            assertEquals(9_000, balance(walletA), context);
        }
        assertEquals(10_000 + 10_000, balance(walletA) + balance(walletB));
    }

    // ---- 3. 풀 고갈 ----

    @Test
    void 풀보다_많은_요청이_잠금에_막히면_5xx는_모두_503이고_잔액은_POSTED만큼만_바뀐다(CapturedOutput output)
            throws Exception {
        int requests = 12;
        long amount = 100;
        UUID walletId = client.openWallet();
        client.charge(walletId, 10_000);
        long startBalance = balance(walletId);
        List<UUID> referenceIds = new ArrayList<>();
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            UUID referenceId = UUID.randomUUID();
            referenceIds.add(referenceId);
            bodies.add(chargeBody(referenceId, walletId, amount));
        }
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<String> poolSamples = new CopyOnWriteArrayList<>();

        List<Call> calls;
        String samples;
        ExecutorService poolSampler = Executors.newSingleThreadExecutor();
        try (LedgerLockProbe probe = newProbe()) {
            Connection holder = probe.holdAccount(accountId(walletId));
            probe.startSampling();
            Instant sentAt = Instant.now();
            poolSampler.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    poolSamples.add(Duration.between(sentAt, Instant.now()).toMillis() + "ms active="
                            + pool.getActiveConnections() + " idle=" + pool.getIdleConnections() + " awaiting="
                            + pool.getThreadsAwaitingConnection());
                    Thread.sleep(50);
                }
                return null;
            });
            LedgerTestClient.Pending pending = client.sendConcurrently(bodies);
            // 정해진 시각(보낸 뒤 4초)에 푼다
            Thread.sleep(4_000);
            probe.release(holder);
            calls = pending.await(RESPONSE_TIMEOUT);
            samples = probe.describeSamples();
        } finally {
            poolSampler.shutdownNow();
        }

        Map<Integer, Long> byStatus = calls.stream()
                .collect(Collectors.groupingBy(Call::status, java.util.TreeMap::new, Collectors.counting()));
        StringBuilder record = new StringBuilder("[db-failure] " + testName + " pool(max=10, connection-timeout=2000ms)"
                + " statusCounts=" + byStatus);
        for (Call call : calls) {
            UUID referenceId = referenceIds.get(call.index());
            record.append("\n[db-failure]   #").append(call.index()).append(" status=").append(call.status())
                    .append(" code=").append(client.errorCode(call.body())).append(" elapsed=")
                    .append(call.elapsedMs()).append("ms exception=").append(chain(thrown.get(referenceId)));
        }
        record.append("\n[db-failure]   poolMaxActive=").append(max(poolSamples, "active="))
                .append(" poolMaxAwaiting=").append(max(poolSamples, "awaiting="))
                .append(" lockWaitSamples=").append(samplesCount(samples));
        System.out.println(record);

        String context = describe(calls) + "\n" + record + "\n" + String.join("\n", poolSamples) + "\n"
                + LedgerSameReferenceIntegrationTest.appLog(output);
        for (Call call : calls) {
            assertServiceUnavailableIf5xx(call, chain(thrown.get(referenceIds.get(call.index()))), context);
        }
        // 고갈이 실제로 일어났는지: 커넥션을 기다린 스레드가 있었고, 커넥션 획득 실패로 끝난 503이 있다
        assertTrue(max(poolSamples, "awaiting=") > 0, "threadsAwaitingConnection 최대값 > 0\n" + context);
        long acquireFailures = calls.stream()
                .filter(call -> call.status() == 503
                        && thrown.get(referenceIds.get(call.index())) instanceof CannotCreateTransactionException)
                .count();
        assertTrue(acquireFailures >= 1, "커넥션 획득 실패(CannotCreateTransactionException)로 끝난 503이 1건 이상\n"
                + context);
        long posted = 0;
        for (UUID referenceId : referenceIds) {
            int rows = transactionRows("CHARGE", referenceId);
            assertTrue(rows <= 1, "reference당 행 1건 이하\n" + context);
            if (rows == 1 && "POSTED".equals(jdbcTemplate.queryForObject(
                    "SELECT status FROM ledger_transactions WHERE reference_type = 'CHARGE' AND reference_id = ?",
                    String.class, referenceId))) {
                posted++;
            }
        }
        assertEquals(startBalance + posted * amount, balance(walletId), "최종 잔액 = 시작 잔액 + POSTED 순변동\n" + context);
    }

    // ---- 도구 ----

    /** 5xx면 503이어야 한다. 아니면 예외 클래스와 원인 연쇄를 담아 실패 */
    private static void assertServiceUnavailableIf5xx(Call call, String chain, String context) {
        if (call.status() >= 500 && call.status() != 503) {
            fail("503이 아닌 5xx: status=" + call.status() + " exception=" + chain + "\n" + context);
        }
    }

    /** 예외 클래스와 원인 연쇄. SQLException은 SQLState를 붙인다 (메시지는 SQL이 섞일 수 있어 남기지 않음) */
    static String chain(Throwable throwable) {
        if (throwable == null) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (Throwable t = throwable; t != null && parts.size() < 10; t = t.getCause()) {
            String part = t.getClass().getName();
            if (t instanceof SQLException sql) {
                part += "[SQLState=" + sql.getSQLState() + "]";
            }
            parts.add(part);
            if (t.getCause() == t) {
                break;
            }
        }
        return String.join(" <- ", parts);
    }

    /** Hibernate SQL 예외 로그(org.hibernate.orm.jdbc.error)의 SQLState 줄 */
    private static String sqlErrorLines(CapturedOutput output) {
        return output.getAll().lines()
                .filter(line -> line.contains("org.hibernate.orm.jdbc.error") && line.contains("SQLState"))
                .map(line -> line.replaceAll(".*\"message\":\"([^\"]*)\".*", "$1"))
                .distinct()
                .collect(Collectors.joining(" | ", "[", "]"));
    }

    private static long max(List<String> poolSamples, String key) {
        return poolSamples.stream()
                .mapToLong(sample -> Long.parseLong(sample.replaceAll(".*" + key + "(\\d+).*", "$1")))
                .max().orElse(-1);
    }

    private static long samplesCount(String samples) {
        return samples.lines().filter(line -> line.contains("wait=Lock/")).count();
    }

    private LedgerLockProbe newProbe() throws SQLException {
        return new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

    private long deadlocks() {
        return jdbcTemplate.queryForObject(
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
    }
}

package com.travelpay.bank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres(Flyway V1~V3 적용)와 실제 HTTP로 출금·환불의 멱등·동시성·장애 주입을 검증한다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mock-bank.fault.delay=1s")
class BankTransactionApiIntegrationTest {

    private static final String SCHEMA = "mock_bank";

    private static final UUID PLATFORM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NORMAL = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID POOR = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID SUSPENDED = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final long NORMAL_SEED = 3_000_000L;

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

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 거래 기록을 지우고 잔액을 시드 값으로 되돌린다. */
    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".bank_transactions");
        jdbcTemplate.update("""
                UPDATE bank_accounts SET balance = CASE id
                    WHEN '00000000-0000-0000-0000-000000000001' THEN 0
                    WHEN '00000000-0000-0000-0000-000000000101' THEN 3000000
                    WHEN '00000000-0000-0000-0000-000000000102' THEN 1500000
                    WHEN '00000000-0000-0000-0000-000000000103' THEN 5000
                    WHEN '00000000-0000-0000-0000-000000000104' THEN 2000000
                END
                """);
    }

    // ---------- 출금 ----------

    @Test
    void 동시에_같은_requestId로_출금하면_한_번만_처리한다() throws Exception {
        String body = withdrawal("w-same", "004", "12340101234567", "유재석", 10_000);

        List<Resp> responses = concurrently(10, () -> post("/withdrawals", body, null));

        assertEquals(1, responses.stream().filter(r -> r.status() == 201).count());
        assertEquals(9, responses.stream().filter(r -> r.status() == 200).count());
        String bankTxId = responses.getFirst().data().get("bankTxId").toString();
        responses.forEach(r -> {
            assertEquals(bankTxId, r.data().get("bankTxId"));
            assertEquals("SUCCEEDED", r.data().get("status"));
        });
        assertEquals(NORMAL_SEED - 10_000, balance(NORMAL));
        assertEquals(10_000, balance(PLATFORM));
        assertEquals(1, transactionCount());
    }

    @Test
    void 업무_거절은_200_FAILED로_기록하고_재요청에_같은_결과를_준다() throws Exception {
        record Case(String requestId, String bankCode, String accountNumber, String holder, String failureCode) {
        }
        List<Case> cases = List.of(
                new Case("w-nf", "004", "0000000000", "유재석", "ACCOUNT_NOT_FOUND"),
                new Case("w-sus", "011", "3021234567891", "유재석", "ACCOUNT_SUSPENDED"),
                new Case("w-holder", "004", "12340101234567", "박지현", "HOLDER_MISMATCH"),
                new Case("w-poor", "020", "1002123456789", "유재석", "INSUFFICIENT_BALANCE"));

        for (Case c : cases) {
            String body = withdrawal(c.requestId(), c.bankCode(), c.accountNumber(), c.holder(), 10_000);
            Resp first = post("/withdrawals", body, null);
            Resp second = post("/withdrawals", body, null);

            assertEquals(200, first.status(), c.requestId());
            assertEquals("FAILED", first.data().get("status"));
            assertEquals(c.failureCode(), first.data().get("failureCode"));
            assertEquals(200, second.status());
            assertEquals(first.data(), second.data());
        }
        assertEquals(4, transactionCount());
        assertEquals(NORMAL_SEED, balance(NORMAL));
        assertEquals(5_000, balance(POOR));
        assertEquals(2_000_000, balance(SUSPENDED));
        assertEquals(0, balance(PLATFORM));
    }

    @Test
    void 예금주는_앞뒤_공백을_정리하고_비교한다() throws Exception {
        Resp resp = post("/withdrawals", withdrawal("w-strip", "004", "12340101234567", "  유재석 ", 1_000), null);

        assertEquals(201, resp.status());
        assertEquals("SUCCEEDED", resp.data().get("status"));
    }

    @Test
    void DELAY는_커밋_후_늦게_응답하고_재요청은_기존_결과를_준다() throws Exception {
        String body = withdrawal("w-delay", "004", "12340101234567", "유재석", 20_000);

        long started = System.nanoTime();
        Resp delayed = post("/withdrawals", body, "DELAY");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Resp retried = post("/withdrawals", body, null);

        assertEquals(201, delayed.status());
        assertTrue(elapsedMs >= 1_000, "지연 " + elapsedMs + "ms");
        assertEquals(200, retried.status());
        assertEquals(delayed.data(), retried.data());
        assertEquals(NORMAL_SEED - 20_000, balance(NORMAL));
        assertEquals(1, transactionCount());
    }

    @Test
    void LOST는_커밋_후_500을_주고_재요청은_성공_결과를_준다() throws Exception {
        String body = withdrawal("w-lost", "004", "12340101234567", "유재석", 30_000);

        Resp lost = post("/withdrawals", body, "LOST");
        Resp retried = post("/withdrawals", body, null);

        assertEquals(500, lost.status());
        assertEquals(false, lost.body().get("success"));
        assertEquals(200, retried.status());
        assertEquals("SUCCEEDED", retried.data().get("status"));
        assertEquals(NORMAL_SEED - 30_000, balance(NORMAL));
        assertEquals(30_000, balance(PLATFORM));
        assertEquals(1, transactionCount());
    }

    @Test
    void 요청_형식이_틀리면_400() throws Exception {
        assertEquals(400, post("/withdrawals", withdrawal("w-v1", "04", "12340101234567", "유재석", 1_000), null).status());
        assertEquals(400, post("/withdrawals", withdrawal("w-v2", "004", "123456789", "유재석", 1_000), null).status());
        assertEquals(400, post("/withdrawals", withdrawal("w-v3", "004", "12340101234567", " ", 1_000), null).status());
        assertEquals(400, post("/withdrawals", withdrawal("w-v4", "004", "12340101234567", "유재석", 0), null).status());
        assertEquals(400, post("/refunds", """
                {"requestId":"r-v1","amount":1000}""", null).status());
        assertEquals(0, transactionCount());
    }

    @Test
    void 같은_requestId를_다른_유형에_쓰면_409() throws Exception {
        String debitId = succeededDebit("dup-key", 1_000);

        Resp resp = post("/refunds", refund("dup-key", debitId, 1_000), null);

        assertEquals(409, resp.status());
        assertEquals(1, transactionCount());
    }

    // ---------- 환불 ----------

    @Test
    void 동시_환불은_하나만_성공하고_나머지는_ALREADY_REFUNDED로_기록한다() throws Exception {
        String debitId = succeededDebit("w-for-refund", 50_000);

        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String body = refund("r-" + i, debitId, 50_000);
            tasks.add(() -> post("/refunds", body, null));
        }
        List<Resp> responses = concurrently(tasks);

        assertEquals(1, responses.stream().filter(r -> r.status() == 201).count());
        List<Resp> rejected = responses.stream().filter(r -> r.status() == 200).toList();
        assertEquals(9, rejected.size());
        rejected.forEach(r -> {
            assertEquals("FAILED", r.data().get("status"));
            assertEquals("ALREADY_REFUNDED", r.data().get("failureCode"));
        });
        assertEquals(NORMAL_SEED, balance(NORMAL));
        assertEquals(0, balance(PLATFORM));
        assertEquals(1, count("type = 'REFUND' AND status = 'SUCCEEDED'"));
        assertEquals(9, count("type = 'REFUND' AND failure_code = 'ALREADY_REFUNDED'"));
    }

    @Test
    void 환불_재요청은_기존_결과를_준다() throws Exception {
        String debitId = succeededDebit("w-r-replay", 7_000);
        String body = refund("r-replay", debitId, 7_000);

        Resp first = post("/refunds", body, "LOST");
        Resp second = post("/refunds", body, null);

        assertEquals(500, first.status());
        assertEquals(200, second.status());
        assertEquals("SUCCEEDED", second.data().get("status"));
        assertEquals(debitId, second.data().get("originalTxId"));
        assertEquals(NORMAL_SEED, balance(NORMAL));
        assertEquals(2, transactionCount());
    }

    @Test
    void 환불_업무_거절() throws Exception {
        String debitId = succeededDebit("w-ok", 10_000);
        String failedDebitId = post("/withdrawals",
                withdrawal("w-fail", "020", "1002123456789", "유재석", 10_000), null).data().get("bankTxId").toString();

        Resp notFound = post("/refunds", refund("r-nf", UUID.randomUUID().toString(), 10_000), null);
        Resp notRefundable = post("/refunds", refund("r-nr", failedDebitId, 10_000), null);
        Resp mismatch = post("/refunds", refund("r-mm", debitId, 9_999), null);

        assertEquals("ORIGINAL_TX_NOT_FOUND", notFound.data().get("failureCode"));
        assertNull(notFound.data().get("originalTxId"));
        assertEquals("ORIGINAL_TX_NOT_REFUNDABLE", notRefundable.data().get("failureCode"));
        assertEquals("AMOUNT_MISMATCH", mismatch.data().get("failureCode"));
        List.of(notFound, notRefundable, mismatch).forEach(r -> assertEquals(200, r.status()));
        assertEquals(NORMAL_SEED - 10_000, balance(NORMAL));
        assertEquals(10_000, balance(PLATFORM));
    }

    @Test
    void 같은_사용자_계좌로_출금과_환불이_동시에_와도_교착_없이_완료된다() throws Exception {
        for (int round = 0; round < 5; round++) {
            setUp();
            String debitId = succeededDebit("w-base-" + round, 100_000);
            String withdrawalBody = withdrawal("w-con-" + round, "004", "12340101234567", "유재석", 40_000);
            String refundBody = refund("r-con-" + round, debitId, 100_000);

            List<Resp> responses = concurrently(List.of(
                    () -> post("/withdrawals", withdrawalBody, null),
                    () -> post("/refunds", refundBody, null)));

            assertEquals(201, responses.get(0).status(), "출금 round " + round);
            assertEquals(201, responses.get(1).status(), "환불 round " + round);
            assertEquals(NORMAL_SEED - 40_000, balance(NORMAL));
            assertEquals(40_000, balance(PLATFORM));
            assertEquals(3, transactionCount());
            assertEquals(3, count("status = 'SUCCEEDED'"));
        }
    }

    // ---------- 조회 ----------

    @Test
    void requestId로_결과를_조회한다() throws Exception {
        String debitId = succeededDebit("w-get", 1_000);

        Resp found = get("/transactions/w-get");
        Resp missing = get("/transactions/none");

        assertEquals(200, found.status());
        assertEquals(debitId, found.data().get("bankTxId"));
        assertEquals(404, missing.status());
    }

    // ---------- helpers ----------

    private String succeededDebit(String requestId, long amount) throws Exception {
        Resp resp = post("/withdrawals", withdrawal(requestId, "004", "12340101234567", "유재석", amount), null);
        assertEquals(201, resp.status());
        return resp.data().get("bankTxId").toString();
    }

    private static String withdrawal(String requestId, String bankCode, String accountNumber, String holder,
                                     long amount) {
        return """
                {"requestId":"%s","bankCode":"%s","accountNumber":"%s","holderName":"%s","amount":%d}"""
                .formatted(requestId, bankCode, accountNumber, holder, amount);
    }

    private static String refund(String requestId, String originalTxId, long amount) {
        return """
                {"requestId":"%s","originalTxId":"%s","amount":%d}""".formatted(requestId, originalTxId, amount);
    }

    private Resp post(String path, String json, String fault) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (fault != null) {
            builder.header("Mock-Fault", fault);
        }
        return send(builder.build());
    }

    private Resp get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET().build());
    }

    @SuppressWarnings("unchecked")
    private Resp send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = jsonMapper.readValue(response.body(), Map.class);
        return new Resp(response.statusCode(), body);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/internal/v1/bank" + path);
    }

    private List<Resp> concurrently(int count, Callable<Resp> task) throws Exception {
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(task);
        }
        return concurrently(tasks);
    }

    /** 모든 작업이 준비된 뒤 한꺼번에 출발시킨다. 결과는 작업 순서대로 */
    private static List<Resp> concurrently(List<Callable<Resp>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Callable<Resp> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private long balance(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM bank_accounts WHERE id = ?", Long.class, accountId);
    }

    private long transactionCount() {
        return count("TRUE");
    }

    private long count(String where) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bank_transactions WHERE " + where, Long.class);
    }

    private record Resp(int status, Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }
}

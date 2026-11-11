package com.travelpay.bank;

import com.travelpay.bank.presentation.fault.BankWithdrawalHolds;
import org.junit.jupiter.api.AfterEach;
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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 은행 코드별 출금 붙잡기(enabled=true): 켠 은행의 출금만 처리 전에 기다렸다가 처리하고, 다른 은행·환불은 영향이 없다.
 * 끄면 바로 원래대로이고 기다리던 요청도 풀린다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mock-bank.fault.enabled=true")
class BankWithdrawalHoldIntegrationTest {

    private static final String SCHEMA = "mock_bank";
    private static final String HOLDS = "/internal/v1/bank/faults/withdrawal-holds";
    /** 붙잡지 않은 요청이 이 안에 끝나는지로 "지연 없음"을 본다 */
    private static final Duration QUICK = Duration.ofSeconds(3);

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
    private BankWithdrawalHolds holds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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

    @AfterEach
    void releaseAll() {
        holds.statuses().forEach(status -> holds.release(status.bankCode()));
    }

    @Test
    void 켠_은행의_출금은_대기_시간_동안_응답이_없고_그_뒤에_처리된다() throws Exception {
        put("004", 3);

        // 클라이언트 읽기 시간 초과(1초)가 대기 시간(3초)보다 짧으면 응답을 받지 못함 → 결과 모름 상황
        assertThrows(HttpTimeoutException.class,
                () -> send(post("/internal/v1/bank/withdrawals", withdrawal004("w-hold-004")), Duration.ofSeconds(1)));
        assertEquals(0L, transactionCount("w-hold-004"));

        // 대기 시간이 지나면 클라이언트가 떠난 뒤에도 처리·커밋된다
        awaitTransaction("w-hold-004", Duration.ofSeconds(10));
        assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                "SELECT status FROM bank_transactions WHERE request_id = 'w-hold-004'", String.class));
    }

    @Test
    void 대기_시간보다_오래_기다리면_원래_응답을_받는다() throws Exception {
        put("004", 1);

        long started = System.nanoTime();
        HttpResponse<String> response = send(post("/internal/v1/bank/withdrawals", withdrawal004("w-hold-wait")),
                Duration.ofSeconds(10));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(201, response.statusCode());
        assertTrue(elapsedMs >= 1_000, "대기 시간만큼 늦게 응답해야 함 elapsedMs=" + elapsedMs);
    }

    @Test
    void 다른_은행의_출금과_환불은_붙잡지_않는다() throws Exception {
        HttpResponse<String> original = send(post("/internal/v1/bank/withdrawals", withdrawal004("w-hold-before")),
                QUICK);
        String originalTxId = (String) data(original).get("bankTxId");
        put("004", 30);

        HttpResponse<String> other = send(post("/internal/v1/bank/withdrawals", """
                {"requestId":"w-hold-088","bankCode":"088","accountNumber":"110123456789",\
                "holderName":"박지현","amount":1000}"""), QUICK);
        HttpResponse<String> refund = send(post("/internal/v1/bank/refunds", """
                {"requestId":"r-hold-004","originalTxId":"%s","amount":1000}""".formatted(originalTxId)), QUICK);

        assertEquals(201, other.statusCode());
        assertEquals(201, refund.statusCode());
    }

    @Test
    void 끄면_새_출금은_바로_처리되고_기다리던_출금도_풀린다() throws Exception {
        put("004", 60);
        CompletableFuture<HttpResponse<String>> waiting = httpClient.sendAsync(
                post("/internal/v1/bank/withdrawals", withdrawal004("w-hold-waiting")).timeout(Duration.ofSeconds(30))
                        .build(), HttpResponse.BodyHandlers.ofString());
        awaitHeldCount("004", 1, Duration.ofSeconds(10));

        HttpResponse<String> released = send(delete("004"), QUICK);
        HttpResponse<String> waited = waiting.get(10, TimeUnit.SECONDS);
        HttpResponse<String> after = send(post("/internal/v1/bank/withdrawals", withdrawal004("w-hold-after")), QUICK);

        assertEquals(200, released.statusCode());
        assertEquals(List.of(), list(released));
        assertEquals(201, waited.statusCode());
        assertEquals(201, after.statusCode());
    }

    @Test
    void 상태_조회는_켠_은행과_대기_시간_붙잡은_수를_은행_코드_순으로_준다() throws Exception {
        put("088", 20);
        HttpResponse<String> enabled = put("004", 30);
        HttpResponse<String> changed = put("004", 40);
        HttpResponse<String> listed = send(HttpRequest.newBuilder(uri(HOLDS)).GET(), QUICK);
        HttpResponse<String> notHeld = send(delete("020"), QUICK);

        assertEquals(200, enabled.statusCode());
        assertEquals(200, listed.statusCode());
        List<Map<String, Object>> statuses = list(listed);
        assertEquals(List.of("004", "088"), statuses.stream().map(status -> status.get("bankCode")).toList());
        assertEquals(40, ((Number) statuses.get(0).get("delaySeconds")).intValue());
        assertEquals(0, ((Number) statuses.get(0).get("heldCount")).intValue());
        assertTrue(statuses.get(0).get("since") instanceof String);
        assertEquals(2, list(changed).size());
        // 켜져 있지 않은 은행을 꺼도 200, 목록은 그대로
        assertEquals(200, notHeld.statusCode());
        assertEquals(2, list(notHeld).size());
    }

    @Test
    void 은행_코드와_대기_시간이_잘못되면_400이고_켜지지_않는다() throws Exception {
        HttpResponse<String> badCode = send(putRequest("04", "{\"delaySeconds\":30}"), QUICK);
        HttpResponse<String> zero = send(putRequest("004", "{\"delaySeconds\":0}"), QUICK);
        HttpResponse<String> tooLong = send(putRequest("004", "{\"delaySeconds\":601}"), QUICK);
        HttpResponse<String> missing = send(putRequest("004", "{}"), QUICK);
        HttpResponse<String> badDelete = send(delete("abc"), QUICK);

        assertEquals(400, badCode.statusCode());
        assertEquals(400, zero.statusCode());
        assertEquals(400, tooLong.statusCode());
        assertEquals(400, missing.statusCode());
        assertEquals(400, badDelete.statusCode());
        assertEquals(List.of(), holds.statuses());
    }

    @Test
    void 붙잡는_중에도_본문이_이상한_출금은_검증_오류_400이다() throws Exception {
        put("004", 1);

        HttpResponse<String> notJson = send(post("/internal/v1/bank/withdrawals", "not-json"), QUICK);
        HttpResponse<String> invalid = send(post("/internal/v1/bank/withdrawals", """
                {"requestId":"w-hold-invalid","bankCode":"004","accountNumber":"12340101234567",\
                "holderName":"유재석","amount":0}"""), Duration.ofSeconds(10));

        assertEquals(400, notJson.statusCode());
        // 은행 코드가 붙잡기 대상이면 기다린 뒤 원래 처리(검증 오류)를 그대로 준다 — 본문을 다시 읽을 수 있어야 함
        assertEquals(400, invalid.statusCode());
    }

    @Test
    void 붙잡기를_덮어써도_끄면_덮어쓰기_전부터_기다리던_출금까지_모두_풀린다() throws Exception {
        put("004", 60);
        CompletableFuture<HttpResponse<String>> before = httpClient.sendAsync(
                post("/internal/v1/bank/withdrawals", withdrawal004("w-hold-before-overwrite"))
                        .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        awaitHeldCount("004", 1, Duration.ofSeconds(10));

        put("004", 2);
        // 덮어쓴 뒤 들어온 출금은 새 대기 시간(2초)을 따른다
        long started = System.nanoTime();
        HttpResponse<String> after = send(post("/internal/v1/bank/withdrawals", withdrawal004("w-hold-after-overwrite")),
                Duration.ofSeconds(10));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(201, after.statusCode());
        assertTrue(elapsedMs >= 2_000 && elapsedMs < 10_000, "새 대기 시간을 따라야 함 elapsedMs=" + elapsedMs);
        // 덮어쓰기 전부터 기다리던 출금은 이전 대기 시간(60초) 그대로 아직 기다린다
        assertTrue(!before.isDone(), "덮어쓰기 전 출금은 아직 기다려야 함");

        send(delete("004"), QUICK);
        HttpResponse<String> released = before.get(5, TimeUnit.SECONDS);
        assertEquals(201, released.statusCode());
    }

    @Test
    void 붙잡는_중_제한을_넘는_출금_본문은_413이고_정상_출금은_그대로_처리된다() throws Exception {
        put("004", 1);
        String oversized = "{\"requestId\":\"w-hold-big\",\"pad\":\"" + "x".repeat(64 * 1024) + "\"}";

        HttpResponse<String> withLength = send(post("/internal/v1/bank/withdrawals", oversized), QUICK);
        HttpResponse<String> chunked = send(chunkedPost(oversized), QUICK);
        HttpResponse<String> normalChunked = send(chunkedPost(withdrawal004("w-hold-chunked")), Duration.ofSeconds(10));

        assertEquals(413, withLength.statusCode());
        assertEquals(413, chunked.statusCode());
        assertEquals("COMMON_PAYLOAD_TOO_LARGE",
                ((Map<?, ?>) jsonMapper.readValue(chunked.body(), Map.class).get("error")).get("code"));
        assertEquals(201, normalChunked.statusCode());
    }

    /** 길이를 모르는 본문이라 Content-Length 없이 Transfer-Encoding: chunked로 보낸다 */
    private HttpRequest.Builder chunkedPost(String body) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(uri("/internal/v1/bank/withdrawals"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes)));
    }

    private HttpResponse<String> put(String bankCode, int delaySeconds) throws Exception {
        HttpResponse<String> response = send(putRequest(bankCode, "{\"delaySeconds\":" + delaySeconds + "}"), QUICK);
        assertEquals(200, response.statusCode());
        return response;
    }

    private HttpRequest.Builder putRequest(String bankCode, String body) {
        return HttpRequest.newBuilder(uri(HOLDS + "/" + bankCode))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder delete(String bankCode) {
        return HttpRequest.newBuilder(uri(HOLDS + "/" + bankCode)).DELETE();
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static String withdrawal004(String requestId) {
        return """
                {"requestId":"%s","bankCode":"004","accountNumber":"12340101234567",\
                "holderName":"유재석","amount":1000}""".formatted(requestId);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, Duration timeout) throws Exception {
        return httpClient.send(builder.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(HttpResponse<String> response) {
        return (Map<String, Object>) jsonMapper.readValue(response.body(), Map.class).get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> response) {
        return (List<Map<String, Object>>) jsonMapper.readValue(response.body(), Map.class).get("data");
    }

    private long transactionCount(String requestId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bank_transactions WHERE request_id = ?",
                Long.class, requestId);
    }

    private void awaitTransaction(String requestId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (transactionCount(requestId) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("거래가 처리되지 않음 requestId=" + requestId);
            }
            Thread.sleep(100);
        }
    }

    private void awaitHeldCount(String bankCode, long expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (holds.statuses().stream().noneMatch(s -> s.bankCode().equals(bankCode) && s.heldCount() >= expected)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("붙잡힌 요청이 없음 bankCode=" + bankCode);
            }
            Thread.sleep(50);
        }
    }
}

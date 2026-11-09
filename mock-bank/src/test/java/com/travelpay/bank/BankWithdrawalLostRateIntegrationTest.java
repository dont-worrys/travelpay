package com.travelpay.bank;

import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 출금 응답 유실 비율 1.0: 정상 처리 응답(2xx)만 커밋 후 500으로 바뀌고, 400·409·환불·Mock-Fault 헤더 요청은 그대로다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock-bank.fault.withdrawal-lost-rate=1.0", "mock-bank.fault.delay=1s"})
class BankWithdrawalLostRateIntegrationTest {

    private static final String SCHEMA = "mock_bank";
    private static final String LOST_METRIC = "mock.bank.fault.withdrawal.lost.rate";

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
    private MeterRegistry meterRegistry;

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

    @Test
    void 성공_출금은_저장되고_500을_주며_재요청도_500이다() throws Exception {
        Map<String, Object> headerLost = headerLostBody();
        double before = lostCount();
        String body = withdrawal("w-rate-ok", "004", "12340101234567", 10_000);

        Resp first = post("/withdrawals", body, null);
        Resp retried = post("/withdrawals", body, null);
        Resp found = get("/transactions/w-rate-ok");

        assertEquals(500, first.status());
        assertEquals(headerLost, first.body());
        assertEquals(500, retried.status());
        assertEquals(headerLost, retried.body());
        assertEquals(200, found.status());
        assertEquals("SUCCEEDED", found.data().get("status"));
        assertEquals(1L, transactionCount("w-rate-ok"));
        assertEquals(2.0, lostCount() - before);
    }

    @Test
    void 업무_실패_FAILED도_저장되고_500을_준다() throws Exception {
        double before = lostCount();

        Resp first = post("/withdrawals", withdrawal("w-rate-suspended", "011", "3021234567891", 10_000), null);
        Resp found = get("/transactions/w-rate-suspended");

        assertEquals(500, first.status());
        assertEquals("FAILED", found.data().get("status"));
        assertEquals("ACCOUNT_SUSPENDED", found.data().get("failureCode"));
        assertEquals(1.0, lostCount() - before);
    }

    @Test
    void 검증_오류_400과_내용이_다른_재요청_409는_그대로다() throws Exception {
        post("/withdrawals", withdrawal("w-rate-conflict", "004", "12340101234567", 10_000), null);
        double before = lostCount();

        Resp invalid = post("/withdrawals", withdrawal("w-rate-invalid", "004", "12340101234567", 0), null);
        Resp conflict = post("/withdrawals", withdrawal("w-rate-conflict", "004", "12340101234567", 20_000), null);

        assertEquals(400, invalid.status());
        assertEquals(409, conflict.status());
        assertEquals("BANK_REQUEST_ID_CONFLICT", ((Map<?, ?>) conflict.body().get("error")).get("code"));
        assertEquals(0.0, lostCount() - before);
    }

    @Test
    void Mock_Fault_헤더가_있으면_비율_판정_없이_헤더_동작만_한다() throws Exception {
        double before = lostCount();

        Resp delayed = post("/withdrawals", withdrawal("w-rate-delay", "004", "12340101234567", 10_000), "DELAY");
        Resp lost = post("/withdrawals", withdrawal("w-rate-lost", "004", "12340101234567", 10_000), "LOST");

        assertEquals(201, delayed.status());
        assertEquals("SUCCEEDED", delayed.data().get("status"));
        assertEquals(500, lost.status());
        assertEquals(0.0, lostCount() - before);
    }

    @Test
    void 환불에는_적용하지_않는다() throws Exception {
        post("/withdrawals", withdrawal("w-rate-refund", "004", "12340101234567", 10_000), null);
        String debitId = get("/transactions/w-rate-refund").data().get("bankTxId").toString();
        double before = lostCount();

        Resp refunded = post("/refunds", """
                {"requestId":"r-rate","originalTxId":"%s","amount":10000}""".formatted(debitId), null);

        assertEquals(201, refunded.status());
        assertEquals("SUCCEEDED", refunded.data().get("status"));
        assertEquals(0.0, lostCount() - before);
    }

    /** 비교 기준: 기존 Mock-Fault: LOST 응답 본문 */
    private Map<String, Object> headerLostBody() throws Exception {
        return post("/withdrawals", withdrawal("w-rate-header-ref", "088", "110123456789", 1_000), "LOST").body();
    }

    private double lostCount() {
        return meterRegistry.get(LOST_METRIC).counter().count();
    }

    private long transactionCount(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_transactions WHERE request_id = ?", Long.class, requestId);
    }

    private static String withdrawal(String requestId, String bankCode, String accountNumber, long amount) {
        return """
                {"requestId":"%s","bankCode":"%s","accountNumber":"%s","holderName":"유재석","amount":%d}"""
                .formatted(requestId, bankCode, accountNumber, amount);
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
        return new Resp(response.statusCode(), jsonMapper.readValue(response.body(), Map.class));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/internal/v1/bank" + path);
    }

    private record Resp(int status, Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }
}

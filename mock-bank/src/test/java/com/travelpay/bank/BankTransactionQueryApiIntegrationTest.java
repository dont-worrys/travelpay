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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres와 실제 HTTP로 거래 조회(단건 GET /transactions/{requestId}, 기간 목록 GET /transactions)를 검증한다.
 * 조회는 거래를 만들거나 바꾸지 않는다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BankTransactionQueryApiIntegrationTest {

    private static final String SCHEMA = "mock_bank";

    private static final UUID PLATFORM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NORMAL = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "bankTxId", "requestId", "type", "status", "failureCode", "amount", "originalTxId",
            "createdAt", "completedAt");

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

    // ---------- 단건 (기존 경로) ----------

    @Test
    void 출금_거래를_조회하면_필드를_그대로_돌려준다() throws Exception {
        Resp created = post("/withdrawals", withdrawal("q-debit", "유재석", 10_000));
        assertEquals(201, created.status());
        String debitId = created.data().get("bankTxId").toString();

        Resp found = get("/transactions/q-debit");

        assertEquals(200, found.status());
        Map<String, Object> data = found.data();
        assertEquals(RESPONSE_FIELDS, data.keySet());
        assertEquals(debitId, data.get("bankTxId"));
        assertEquals("q-debit", data.get("requestId"));
        assertEquals("DEBIT", data.get("type"));
        assertEquals("SUCCEEDED", data.get("status"));
        assertNull(data.get("failureCode"));
        assertEquals(10_000, ((Number) data.get("amount")).longValue());
        assertNull(data.get("originalTxId"));
        assertStoredTimes(debitId, data);
        assertEquals(created.data(), data);
    }

    @Test
    void 환불_거래를_조회하면_원거래_ID를_돌려준다() throws Exception {
        String debitId = post("/withdrawals", withdrawal("q-debit", "유재석", 10_000)).data().get("bankTxId").toString();
        Resp created = post("/refunds", refund("q-refund", debitId, 10_000));
        assertEquals(201, created.status());
        String refundId = created.data().get("bankTxId").toString();

        Resp found = get("/transactions/q-refund");

        assertEquals(200, found.status());
        Map<String, Object> data = found.data();
        assertEquals(refundId, data.get("bankTxId"));
        assertEquals("q-refund", data.get("requestId"));
        assertEquals("REFUND", data.get("type"));
        assertEquals("SUCCEEDED", data.get("status"));
        assertNull(data.get("failureCode"));
        assertEquals(10_000, ((Number) data.get("amount")).longValue());
        assertEquals(debitId, data.get("originalTxId"));
        assertStoredTimes(refundId, data);
    }

    @Test
    void 실패한_거래를_조회하면_실패_코드를_돌려준다() throws Exception {
        Resp created = post("/withdrawals", withdrawal("q-failed", "다른사람", 10_000));
        assertEquals(200, created.status());

        Resp found = get("/transactions/q-failed");

        assertEquals(200, found.status());
        assertEquals("FAILED", found.data().get("status"));
        assertEquals("HOLDER_MISMATCH", found.data().get("failureCode"));
    }

    @Test
    void 없는_requestId는_404() throws Exception {
        Resp missing = get("/transactions/none");

        assertEquals(404, missing.status());
        assertEquals(false, missing.body().get("success"));
    }

    @Test
    void 반복_조회해도_결과가_같고_거래가_바뀌지_않는다() throws Exception {
        String debitId = post("/withdrawals", withdrawal("q-debit", "유재석", 10_000)).data().get("bankTxId").toString();
        post("/refunds", refund("q-refund", debitId, 10_000));
        post("/withdrawals", withdrawal("q-failed", "다른사람", 5_000));
        List<Map<String, Object>> before = snapshot();
        long platformBefore = balance(PLATFORM);
        long normalBefore = balance(NORMAL);

        Map<String, Object> first = get("/transactions/q-debit").data();
        for (int i = 0; i < 10; i++) {
            Resp found = get("/transactions/q-debit");
            assertEquals(200, found.status());
            assertEquals(first, found.data());
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(404, get("/transactions/never-created").status());
        }

        assertEquals(before, snapshot());
        assertEquals(3, before.size());
        assertEquals(platformBefore, balance(PLATFORM));
        assertEquals(normalBefore, balance(NORMAL));
    }

    // ---------- 기간 목록 ----------

    @Test
    void 범위는_from을_포함하고_to를_제외한다() throws Exception {
        insert(uuid(1), "before", BASE.minusNanos(1_000));
        insert(uuid(2), "at-from", BASE);
        insert(uuid(3), "inside", BASE.plusSeconds(1));
        insert(uuid(4), "at-to", BASE.plusSeconds(2));

        Resp resp = list(BASE, BASE.plusSeconds(2), null);

        assertEquals(200, resp.status());
        assertEquals(List.of("at-from", "inside"), requestIds(resp));
        assertEquals(2, ((Number) resp.data().get("totalElements")).longValue());
    }

    @Test
    void from과_to가_같으면_빈_목록() throws Exception {
        insert(uuid(1), "at-from", BASE);

        Resp resp = list(BASE, BASE, null);

        assertEquals(200, resp.status());
        assertEmptyPage(resp);
    }

    @Test
    void 범위에_거래가_없으면_빈_페이지() throws Exception {
        insert(uuid(1), "elsewhere", BASE.minusSeconds(3600));

        Resp resp = list(BASE, BASE.plusSeconds(60), null);

        assertEquals(200, resp.status());
        assertEmptyPage(resp);
    }

    @Test
    void 생성_시각이_같으면_id_오름차순() throws Exception {
        insert(uuid(3), "c", BASE);
        insert(uuid(1), "a", BASE);
        insert(uuid(4), "d", BASE);
        insert(uuid(2), "b", BASE);
        insert(uuid(5), "earlier", BASE.minusNanos(1_000));

        Resp resp = list(BASE.minusSeconds(1), BASE.plusSeconds(1), null);

        assertEquals(List.of("earlier", "a", "b", "c", "d"), requestIds(resp));
        assertEquals(List.of(uuid(5), uuid(1), uuid(2), uuid(3), uuid(4)).stream().map(UUID::toString).toList(),
                items(resp).stream().map(item -> item.get("bankTxId")).toList());
    }

    @Test
    void 여러_페이지를_이어_받아도_중복과_누락이_없다() throws Exception {
        for (int i = 0; i < 25; i++) {
            // 5건씩 같은 시각으로 묶어 동률 경계가 페이지 경계에 걸치게 한다
            insert(UUID.randomUUID(), "p-" + i, BASE.plusSeconds(i / 5));
        }
        List<String> expected = jdbcTemplate.queryForList(
                "SELECT request_id FROM bank_transactions ORDER BY created_at, id", String.class);

        List<String> collected = new ArrayList<>();
        int totalPages = -1;
        for (int page = 0; totalPages < 0 || page < totalPages; page++) {
            Resp resp = list(BASE, BASE.plusSeconds(60), "&page=" + page + "&size=7");
            assertEquals(200, resp.status());
            totalPages = ((Number) resp.data().get("totalPages")).intValue();
            collected.addAll(requestIds(resp));
        }

        assertEquals(4, totalPages);
        assertEquals(25, collected.size());
        assertEquals(25, new HashSet<>(collected).size());
        assertEquals(expected, collected);
    }

    @Test
    void 오프셋이_있는_시각은_UTC로_해석한다() throws Exception {
        insert(uuid(1), "utc-midnight", BASE);
        insert(uuid(2), "a-second-before", BASE.minusSeconds(1));

        Resp resp = get("/transactions?from=2026-01-01T09:00:00%2B09:00&to=2026-01-01T09:00:01%2B09:00");

        assertEquals(200, resp.status());
        assertEquals(List.of("utc-midnight"), requestIds(resp));
    }

    @Test
    void sort_파라미터를_보내도_고정_정렬을_유지한다() throws Exception {
        insert(uuid(2), "b", BASE);
        insert(uuid(1), "a", BASE);
        insert(uuid(3), "later", BASE.plusSeconds(1));

        Resp resp = list(BASE, BASE.plusSeconds(10), "&sort=createdAt,desc&sort=id,desc&sort=amount");

        assertEquals(200, resp.status());
        assertEquals(List.of("a", "b", "later"), requestIds(resp));
    }

    @Test
    void 목록_입력이_틀리면_400() throws Exception {
        String from = BASE.toString();
        String to = BASE.plusSeconds(1).toString();

        assertEquals(400, get("/transactions").status());
        assertEquals(400, get("/transactions?from=" + from).status());
        assertEquals(400, get("/transactions?to=" + to).status());
        assertEquals(400, get("/transactions?from=" + to + "&to=" + from).status());
        assertEquals(400, get("/transactions?from=2026-01-01&to=" + to).status());
        assertEquals(400, get("/transactions?from=not-a-date&to=" + to).status());
        assertEquals(400, get("/transactions?from=" + from + "&to=" + to + "&page=-1").status());
        assertEquals(400, get("/transactions?from=" + from + "&to=" + to + "&size=0").status());
        assertEquals(400, get("/transactions?from=" + from + "&to=" + to + "&size=-1").status());
        assertEquals(400, get("/transactions?from=" + from + "&to=" + to + "&page=x").status());
        assertEquals(0, transactionCount());
    }

    @Test
    void size는_기본_20이고_최대_100() throws Exception {
        for (int i = 0; i < 101; i++) {
            insert(UUID.randomUUID(), "s-" + i, BASE.plusMillis(i));
        }

        Resp defaults = list(BASE, BASE.plusSeconds(1), null);
        Resp max = list(BASE, BASE.plusSeconds(1), "&size=100");
        Resp over = list(BASE, BASE.plusSeconds(1), "&size=101");

        assertEquals(0, ((Number) defaults.data().get("page")).intValue());
        assertEquals(20, ((Number) defaults.data().get("size")).intValue());
        assertEquals(20, items(defaults).size());
        assertEquals(100, ((Number) max.data().get("size")).intValue());
        assertEquals(100, items(max).size());
        assertEquals(100, ((Number) over.data().get("size")).intValue());
        assertEquals(100, items(over).size());
        assertEquals(101, ((Number) over.data().get("totalElements")).longValue());
        assertEquals(2, ((Number) over.data().get("totalPages")).intValue());
    }

    @Test
    void offset이_int_범위를_넘는_page는_400() throws Exception {
        insert(uuid(1), "only", BASE);
        String range = "/transactions?from=" + BASE.minusSeconds(1) + "&to=" + BASE.plusSeconds(1);

        Resp over = get(range + "&page=21474837&size=100");        // offset 2,147,483,700
        Resp overAfterClamp = get(range + "&page=21474837&size=101"); // 101 → 100 보정 뒤 계산
        Resp below = get(range + "&page=21474836&size=100");       // offset 2,147,483,600

        assertEquals(400, over.status());
        assertEquals(400, overAfterClamp.status());
        assertEquals(200, below.status());
        assertTrue(items(below).isEmpty());
        assertEquals(1, ((Number) below.data().get("totalElements")).longValue());
    }

    @Test
    void 마이크로초보다_세밀한_from은_올려서_경계_거래를_제외한다() throws Exception {
        insert(uuid(1), "at-base", BASE);

        Resp resp = list(BASE.plusNanos(100), BASE.plusSeconds(1), null);

        assertEquals(200, resp.status());
        assertEmptyPage(resp);
    }

    @Test
    void 마이크로초보다_세밀한_to는_올려서_경계_거래를_포함한다() throws Exception {
        insert(uuid(1), "at-base", BASE);

        Resp resp = list(BASE.minusSeconds(1), BASE.plusNanos(100), null);

        assertEquals(200, resp.status());
        assertEquals(List.of("at-base"), requestIds(resp));
    }

    @Test
    void 마이크로초_단위_경계는_그대로_비교한다() throws Exception {
        insert(uuid(1), "at-base", BASE);

        assertEquals(List.of("at-base"), requestIds(list(BASE, BASE.plusNanos(1_000), null)));
        assertTrue(items(list(BASE.plusNanos(1_000), BASE.plusSeconds(1), null)).isEmpty());
        assertTrue(items(list(BASE.minusSeconds(1), BASE, null)).isEmpty());
    }

    @Test
    void 목록_응답에는_계좌_정보가_없다() throws Exception {
        String debitId = post("/withdrawals", withdrawal("l-debit", "유재석", 10_000)).data().get("bankTxId").toString();
        post("/refunds", refund("l-refund", debitId, 10_000));
        post("/withdrawals", withdrawal("l-failed", "다른사람", 5_000));

        Resp resp = list(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), null);

        assertEquals(200, resp.status());
        List<Map<String, Object>> items = items(resp);
        assertEquals(List.of("l-debit", "l-refund", "l-failed"), requestIds(resp));
        for (Map<String, Object> item : items) {
            assertEquals(RESPONSE_FIELDS, item.keySet());
            String json = jsonMapper.writeValueAsString(item);
            assertFalse(json.contains(NORMAL.toString()), json);
            assertFalse(json.contains(PLATFORM.toString()), json);
            assertFalse(json.contains("12340101234567"), json);
            assertFalse(json.contains("유재석"), json);
        }
        assertEquals(debitId, items.get(1).get("originalTxId"));
        assertEquals("HOLDER_MISMATCH", items.get(2).get("failureCode"));
        assertEquals(3, transactionCount());
    }

    // ---------- helpers ----------

    private void assertStoredTimes(String txId, Map<String, Object> data) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT created_at, completed_at FROM bank_transactions WHERE id = ?::uuid", txId);
        assertNotNull(data.get("createdAt"));
        assertEquals(toInstant(row.get("created_at")), Instant.parse(data.get("createdAt").toString()));
        assertEquals(toInstant(row.get("completed_at")), Instant.parse(data.get("completedAt").toString()));
    }

    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("unexpected type " + value.getClass());
    }

    private static void assertEmptyPage(Resp resp) {
        assertTrue(items(resp).isEmpty());
        assertEquals(0, ((Number) resp.data().get("totalElements")).longValue());
        assertEquals(0, ((Number) resp.data().get("totalPages")).intValue());
    }

    /** 성공한 출금을 고정 시각으로 직접 넣는다 */
    private void insert(UUID id, String requestId, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO bank_transactions
                    (id, request_id, type, from_account_id, to_account_id, amount, status, created_at, completed_at)
                VALUES (?, ?, 'DEBIT', ?, ?, 1000, 'SUCCEEDED', ?::timestamptz, ?::timestamptz)
                """, id, requestId, NORMAL, PLATFORM, createdAt.toString(), createdAt.toString());
    }

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    private List<Map<String, Object>> snapshot() {
        return jdbcTemplate.queryForList(
                "SELECT id, request_id, type, status, failure_code, amount, original_tx_id, created_at, completed_at "
                        + "FROM bank_transactions ORDER BY id");
    }

    private Resp list(Instant from, Instant to, String extra) throws Exception {
        return get("/transactions?from=" + from + "&to=" + to + (extra == null ? "" : extra));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Resp resp) {
        return (List<Map<String, Object>>) resp.data().get("items");
    }

    private static List<String> requestIds(Resp resp) {
        return items(resp).stream().map(item -> item.get("requestId").toString()).toList();
    }

    private static String withdrawal(String requestId, String holder, long amount) {
        return """
                {"requestId":"%s","bankCode":"004","accountNumber":"12340101234567","holderName":"%s","amount":%d}"""
                .formatted(requestId, holder, amount);
    }

    private static String refund(String requestId, String originalTxId, long amount) {
        return """
                {"requestId":"%s","originalTxId":"%s","amount":%d}""".formatted(requestId, originalTxId, amount);
    }

    private Resp post(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
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

    private long balance(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM bank_accounts WHERE id = ?", Long.class, accountId);
    }

    private long transactionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bank_transactions", Long.class);
    }

    private record Resp(int status, Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }
    }
}

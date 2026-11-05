package com.travelpay.ledger;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres와 실제 HTTP로 거래 조회 응답의 항목(entries)을 확인한다.
 * 등록 API로 거래를 만든 뒤 조회 API 응답을 등록 요청·DB 값과 비교한다.
 * 테스트마다 새 지갑·새 referenceId를 써서 실행 순서와 무관하다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.SQL=DEBUG"
})
class LedgerTransactionQueryIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final String TRANSACTIONS = "/internal/v1/ledger/transactions";

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
    private EntityManagerFactory entityManagerFactory;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void 충전_두_항목은_등록한_계정_통화_방향_금액_유형_그대로_조회된다() throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();
        List<Map<String, Object>> requested = List.of(
                system("CHARGE_SOURCE", "KRW", "DEBIT", 5_000, "CHARGE_IN"),
                user(walletId, "KRW", "CREDIT", 5_000, "CHARGE_IN"));
        JsonNode posted = registerData("CHARGE", referenceId, requested, 201);

        JsonNode data = getData("CHARGE", referenceId);

        assertEquals(posted.get("transactionId").asString(), data.get("transactionId").asString());
        assertEquals("POSTED", data.get("status").asString());
        assertTrue(data.get("rejectReason").isNull());
        assertEquals("CHARGE", data.get("referenceType").asString());
        assertEquals(referenceId.toString(), data.get("referenceId").asString());
        assertEquals(createdAtInDb("CHARGE", referenceId), Instant.parse(data.get("createdAt").asString()));
        assertEntriesMatchOneToOne(expectedResponseEntries(requested), data.get("entries"));
    }

    @Test
    void 환전_여섯_항목은_항목마다_계정_통화_방향_금액_유형이_요청과_같다() throws Exception {
        UUID walletId = openWallet();
        charge(walletId, 10_000);
        UUID referenceId = UUID.randomUUID();
        List<Map<String, Object>> requested = List.of(
                user(walletId, "KRW", "DEBIT", 9_900, "EXCHANGE_OUT"),
                system("FX_POSITION", "KRW", "CREDIT", 9_900, "EXCHANGE_OUT"),
                user(walletId, "KRW", "DEBIT", 100, "EXCHANGE_FEE"),
                system("FEE_REVENUE", "KRW", "CREDIT", 100, "EXCHANGE_FEE"),
                system("FX_POSITION", "JPY", "DEBIT", 1_050, "EXCHANGE_IN"),
                user(walletId, "JPY", "CREDIT", 1_050, "EXCHANGE_IN"));
        registerData("EXCHANGE", referenceId, requested, 201);

        JsonNode data = getData("EXCHANGE", referenceId);

        assertEquals("POSTED", data.get("status").asString());
        assertEntriesMatchOneToOne(expectedResponseEntries(requested), data.get("entries"));
    }

    @Test
    void 거절된_거래는_항목이_빈_배열이다() throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();
        List<Map<String, Object>> requested = List.of(
                user(walletId, "KRW", "DEBIT", 1_000, "CHARGE_IN"),
                system("CHARGE_SOURCE", "KRW", "CREDIT", 1_000, "CHARGE_IN"));
        HttpResponse<String> rejected = post(TRANSACTIONS, registerBody("CHARGE", referenceId, requested));
        assertEquals(422, rejected.statusCode(), rejected.body());

        JsonNode data = getData("CHARGE", referenceId);

        assertEquals("REJECTED", data.get("status").asString());
        assertEquals("INSUFFICIENT_BALANCE", data.get("rejectReason").asString());
        assertTrue(data.get("entries").isArray());
        assertEquals(0, data.get("entries").size());
    }

    @Test
    void 거래가_없으면_404다() throws Exception {
        HttpResponse<String> response = get("CHARGE", UUID.randomUUID());

        assertEquals(404, response.statusCode(), response.body());
        JsonNode body = jsonMapper.readTree(response.body());
        assertFalse(body.get("success").asBoolean());
        assertEquals("LEDGER_TRANSACTION_NOT_FOUND", body.get("error").get("code").asString());
    }

    @Test
    void 응답에_balanceAfter가_없다() throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = charge(walletId, 3_000);

        HttpResponse<String> response = get("CHARGE", referenceId);

        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("balanceAfter"), response.body());
        for (JsonNode entry : jsonMapper.readTree(response.body()).get("data").get("entries")) {
            assertFalse(entry.has("balanceAfter"));
        }
    }

    @Test
    void 반복_조회해도_항목_순서가_같고_항목_id_순이다() throws Exception {
        UUID walletId = openWallet();
        charge(walletId, 10_000);
        UUID referenceId = UUID.randomUUID();
        registerData("EXCHANGE", referenceId, List.of(
                user(walletId, "KRW", "DEBIT", 9_900, "EXCHANGE_OUT"),
                system("FX_POSITION", "KRW", "CREDIT", 9_900, "EXCHANGE_OUT"),
                user(walletId, "KRW", "DEBIT", 100, "EXCHANGE_FEE"),
                system("FEE_REVENUE", "KRW", "CREDIT", 100, "EXCHANGE_FEE"),
                system("FX_POSITION", "JPY", "DEBIT", 1_050, "EXCHANGE_IN"),
                user(walletId, "JPY", "CREDIT", 1_050, "EXCHANGE_IN")), 201);

        List<String> first = entryKeys(getData("EXCHANGE", referenceId).get("entries"));
        for (int i = 0; i < 2; i++) {
            assertEquals(first, entryKeys(getData("EXCHANGE", referenceId).get("entries")));
        }
        assertEquals(entryKeysInIdOrder("EXCHANGE", referenceId), first);
    }

    @Test
    void 항목_수와_상관없이_조회_SQL은_거래_한_번_항목_JOIN_한_번이다() throws Exception {
        UUID walletId = openWallet();
        charge(walletId, 10_000);
        UUID referenceId = UUID.randomUUID();
        registerData("EXCHANGE", referenceId, List.of(
                user(walletId, "KRW", "DEBIT", 9_900, "EXCHANGE_OUT"),
                system("FX_POSITION", "KRW", "CREDIT", 9_900, "EXCHANGE_OUT"),
                user(walletId, "KRW", "DEBIT", 100, "EXCHANGE_FEE"),
                system("FEE_REVENUE", "KRW", "CREDIT", 100, "EXCHANGE_FEE"),
                system("FX_POSITION", "JPY", "DEBIT", 1_050, "EXCHANGE_IN"),
                user(walletId, "JPY", "CREDIT", 1_050, "EXCHANGE_IN")), 201);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        JsonNode data = getData("EXCHANGE", referenceId);

        assertEquals(6, data.get("entries").size());
        assertEquals(2, statistics.getPrepareStatementCount());
        assertEquals(1, statistics.getEntityLoadCount(), "거래 엔티티 1건 외에 계정·항목 엔티티를 읽지 않아야 함");
    }

    // ---- 요청·기대값 ----

    private static Map<String, Object> user(UUID walletId, String currency, String direction, long amount,
                                            String entryType) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ownerType", "USER");
        entry.put("walletId", walletId.toString());
        entry.put("currency", currency);
        entry.put("direction", direction);
        entry.put("amount", amount);
        entry.put("entryType", entryType);
        return entry;
    }

    private static Map<String, Object> system(String accountType, String currency, String direction, long amount,
                                              String entryType) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ownerType", "SYSTEM");
        entry.put("accountType", accountType);
        entry.put("currency", currency);
        entry.put("direction", direction);
        entry.put("amount", amount);
        entry.put("entryType", entryType);
        return entry;
    }

    /** 응답은 계정에 저장된 값 그대로: USER 계정은 accountType=WALLET, SYSTEM 계정은 walletId=null */
    private static List<Map<String, Object>> expectedResponseEntries(List<Map<String, Object>> requested) {
        List<Map<String, Object>> expected = new ArrayList<>();
        for (Map<String, Object> entry : requested) {
            boolean isUser = "USER".equals(entry.get("ownerType"));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ownerType", entry.get("ownerType"));
            e.put("walletId", isUser ? entry.get("walletId") : null);
            e.put("accountType", isUser ? "WALLET" : entry.get("accountType"));
            e.put("currency", entry.get("currency"));
            e.put("direction", entry.get("direction"));
            e.put("amount", entry.get("amount"));
            e.put("entryType", entry.get("entryType"));
            expected.add(e);
        }
        return expected;
    }

    /** 항목마다 7개 필드를 모두 비교해 요청 항목과 응답 항목이 1:1로 대응하는지 확인 (개수만 보지 않음) */
    private static void assertEntriesMatchOneToOne(List<Map<String, Object>> expected, JsonNode entries) {
        assertTrue(entries.isArray());
        assertEquals(expected.size(), new HashSet<>(expected).size(), "기대 항목끼리 겹치지 않아야 1:1 비교가 됨");
        List<Map<String, Object>> actual = new ArrayList<>();
        for (JsonNode entry : entries) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("ownerType", entry.get("ownerType").asString());
            a.put("walletId", entry.get("walletId").isNull() ? null : entry.get("walletId").asString());
            a.put("accountType", entry.get("accountType").isNull() ? null : entry.get("accountType").asString());
            a.put("currency", entry.get("currency").asString());
            a.put("direction", entry.get("direction").asString());
            a.put("amount", entry.get("amount").asLong());
            a.put("entryType", entry.get("entryType").asString());
            assertEquals(7, entry.size(), "항목 필드는 7개: " + entry);
            actual.add(a);
        }
        assertEquals(expected.size(), actual.size(), actual.toString());
        for (Map<String, Object> e : expected) {
            assertEquals(1, actual.stream().filter(e::equals).count(), "응답에 없는 항목: " + e + " / 응답: " + actual);
        }
    }

    private static List<String> entryKeys(JsonNode entries) {
        List<String> keys = new ArrayList<>();
        for (JsonNode entry : entries) {
            keys.add(entry.get("currency").asString() + "|" + entry.get("direction").asString() + "|"
                    + entry.get("amount").asLong() + "|" + entry.get("entryType").asString());
        }
        return keys;
    }

    // ---- HTTP ----

    private UUID openWallet() throws Exception {
        UUID walletId = UUID.randomUUID();
        HttpResponse<String> response = post("/internal/v1/ledger/accounts",
                jsonMapper.writeValueAsString(Map.of("walletId", walletId)));
        assertEquals(201, response.statusCode(), response.body());
        return walletId;
    }

    /** 지갑 KRW 충전. 거래 referenceId를 돌려준다 */
    private UUID charge(UUID walletId, long amount) throws Exception {
        UUID referenceId = UUID.randomUUID();
        registerData("CHARGE", referenceId, List.of(
                system("CHARGE_SOURCE", "KRW", "DEBIT", amount, "CHARGE_IN"),
                user(walletId, "KRW", "CREDIT", amount, "CHARGE_IN")), 201);
        return referenceId;
    }

    private JsonNode registerData(String referenceType, UUID referenceId, List<Map<String, Object>> entries,
                                  int expectedStatus) throws Exception {
        HttpResponse<String> response = post(TRANSACTIONS, registerBody(referenceType, referenceId, entries));
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return jsonMapper.readTree(response.body()).get("data");
    }

    private String registerBody(String referenceType, UUID referenceId, List<Map<String, Object>> entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceType", referenceType);
        body.put("referenceId", referenceId.toString());
        body.put("entries", entries);
        return jsonMapper.writeValueAsString(body);
    }

    private JsonNode getData(String referenceType, UUID referenceId) throws Exception {
        HttpResponse<String> response = get(referenceType, referenceId);
        assertEquals(200, response.statusCode(), response.body());
        return jsonMapper.readTree(response.body()).get("data");
    }

    private HttpResponse<String> get(String referenceType, UUID referenceId) throws Exception {
        URI uri = URI.create("http://localhost:" + port + TRANSACTIONS
                + "?referenceType=" + referenceType + "&referenceId=" + referenceId);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ---- DB ----

    private Instant createdAtInDb(String referenceType, UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at FROM ledger_transactions WHERE reference_type = ? AND reference_id = ?",
                Timestamp.class, referenceType, referenceId).toInstant();
    }

    private List<String> entryKeysInIdOrder(String referenceType, UUID referenceId) {
        return jdbcTemplate.query("""
                SELECT e.currency, e.direction, e.amount, e.entry_type FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE t.reference_type = ? AND t.reference_id = ?
                ORDER BY e.id
                """, (rs, i) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getLong(3) + "|"
                + rs.getString(4), referenceType, referenceId);
    }
}

package com.travelpay.ledger;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.ledger.application.transaction.LedgerTransactionService;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.EntryType;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Postgres(Flyway V1~V5)와 실제 HTTP로 거래 항목 유형(entryType) 검증을 확인한다.
 * 테스트마다 새 지갑·새 referenceId를 쓰고 해당 거래·지갑 값만 확인하므로 실행 순서와 무관하다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service"
})
class LedgerEntryTypeIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final long AMOUNT = 1_000L;

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
    private LedgerTransactionService transactionService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @ParameterizedTest
    @EnumSource(EntryType.class)
    void 합의한_항목_유형은_모두_등록된다(EntryType entryType) throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();

        HttpResponse<String> response = register(referenceId, walletId, entryType.name());

        assertEquals(201, response.statusCode(), response.body());
        JsonNode data = jsonMapper.readTree(response.body()).get("data");
        assertEquals("POSTED", data.get("status").asString());
        assertEquals(List.of(entryType.name(), entryType.name()), entryTypesOf(referenceId));
        assertEquals(AMOUNT, walletBalance(walletId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARGE", "charge_in"})
    void 목록_밖의_항목_유형은_400이고_아무것도_기록하지_않는다(String entryType) throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();
        long balanceBefore = walletBalance(walletId);

        HttpResponse<String> response = register(referenceId, walletId, entryType);

        assertEquals(400, response.statusCode(), response.body());
        JsonNode body = jsonMapper.readTree(response.body());
        assertEquals(false, body.get("success").asBoolean());
        assertEquals("COMMON_INVALID_REQUEST", body.get("error").get("code").asString());
        assertEquals(0, transactionCountOf(referenceId));
        assertEquals(List.of(), entryTypesOf(referenceId));
        assertEquals(balanceBefore, walletBalance(walletId));
    }

    @Test
    void 같은_요청을_다시_보내면_200이고_해시와_잔액이_그대로다() throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();
        HttpResponse<String> first = register(referenceId, walletId, EntryType.CHARGE_IN.name());
        assertEquals(201, first.statusCode(), first.body());
        String transactionId = jsonMapper.readTree(first.body()).get("data").get("transactionId").asString();
        String hashBefore = requestHashOf(referenceId);
        long balanceBefore = walletBalance(walletId);

        HttpResponse<String> retry = register(referenceId, walletId, EntryType.CHARGE_IN.name());

        assertEquals(200, retry.statusCode(), retry.body());
        JsonNode data = jsonMapper.readTree(retry.body()).get("data");
        assertEquals(transactionId, data.get("transactionId").asString());
        assertEquals("POSTED", data.get("status").asString());
        assertEquals(hashBefore, requestHashOf(referenceId));
        assertEquals(1, transactionCountOf(referenceId));
        assertEquals(2, entryTypesOf(referenceId).size());
        assertEquals(balanceBefore, walletBalance(walletId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "\"0\""})
    void entryType에_숫자나_숫자_문자열은_400이다(String entryTypeLiteral) throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();

        HttpResponse<String> response = registerRaw(referenceId, walletId, Map.of("entryType", entryTypeLiteral));

        assertInvalidAndNothingRecorded(response, referenceId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"referenceType", "ownerType", "accountType", "currency", "direction"})
    void 다른_enum_필드에도_숫자는_400이다(String field) throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();

        HttpResponse<String> response = registerRaw(referenceId, walletId, Map.of(field, "0"));

        assertInvalidAndNothingRecorded(response, referenceId);
    }

    @Test
    void 정상_문자열_enum_값은_그대로_등록된다() throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();

        HttpResponse<String> response = registerRaw(referenceId, walletId,
                Map.of("currency", quoted("KRW"), "entryType", quoted("CHARGE_IN")));

        assertEquals(201, response.statusCode(), response.body());
        assertEquals(List.of("CHARGE_IN", "CHARGE_IN"), entryTypesOf(referenceId));
        assertEquals(AMOUNT, walletBalance(walletId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARGE", "charge_in"})
    void 서비스에_허용_밖_entryType_명령이_오면_DB에_닿기_전에_거절한다(String entryType) throws Exception {
        UUID walletId = openWallet();
        UUID referenceId = UUID.randomUUID();
        long balanceBefore = walletBalance(walletId);
        RegisterTransactionCommand command = new RegisterTransactionCommand(
                ReferenceType.CHARGE, referenceId, null, null, null, List.of(
                new RegisterTransactionCommand.Entry(OwnerType.SYSTEM, null, AccountType.CHARGE_SOURCE,
                        Currency.KRW, Direction.DEBIT, AMOUNT, entryType),
                new RegisterTransactionCommand.Entry(OwnerType.USER, walletId, null,
                        Currency.KRW, Direction.CREDIT, AMOUNT, entryType)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> transactionService.register(command));

        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
        assertEquals(0, transactionCountOf(referenceId));
        assertEquals(List.of(), entryTypesOf(referenceId));
        assertEquals(balanceBefore, walletBalance(walletId));
    }

    private UUID openWallet() throws Exception {
        UUID walletId = UUID.randomUUID();
        HttpResponse<String> response = post("/internal/v1/ledger/accounts",
                jsonMapper.writeValueAsString(Map.of("walletId", walletId)));
        assertTrue(response.statusCode() == 201, response.body());
        return walletId;
    }

    private HttpResponse<String> register(UUID referenceId, UUID walletId, String entryType) throws Exception {
        return registerRaw(referenceId, walletId, Map.of("entryType", quoted(entryType)));
    }

    /**
     * 충전 자금원(SYSTEM, 음수 허용) DEBIT → 지갑 KRW CREDIT. 두 항목 모두 같은 entryType·currency.
     * overrides는 enum 필드 이름 → JSON 리터럴(따옴표 포함 문자열 또는 숫자). ownerType·accountType·direction은 SYSTEM 항목에만 적용
     */
    private HttpResponse<String> registerRaw(UUID referenceId, UUID walletId, Map<String, String> overrides)
            throws Exception {
        String body = """
                {
                  "referenceType": %s,
                  "referenceId": "%s",
                  "entries": [
                    {"ownerType": %s, "accountType": %s, "currency": %s,
                     "direction": %s, "amount": %d, "entryType": %s},
                    {"ownerType": "USER", "walletId": "%s", "currency": %s,
                     "direction": "CREDIT", "amount": %d, "entryType": %s}
                  ]
                }
                """.formatted(
                overrides.getOrDefault("referenceType", quoted("CHARGE")), referenceId,
                overrides.getOrDefault("ownerType", quoted("SYSTEM")),
                overrides.getOrDefault("accountType", quoted("CHARGE_SOURCE")),
                overrides.getOrDefault("currency", quoted("KRW")),
                overrides.getOrDefault("direction", quoted("DEBIT")), AMOUNT,
                overrides.getOrDefault("entryType", quoted("CHARGE_IN")),
                walletId, overrides.getOrDefault("currency", quoted("KRW")), AMOUNT,
                overrides.getOrDefault("entryType", quoted("CHARGE_IN")));
        return post("/internal/v1/ledger/transactions", body);
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private void assertInvalidAndNothingRecorded(HttpResponse<String> response, UUID referenceId) {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode body = jsonMapper.readTree(response.body());
        assertEquals("COMMON_INVALID_REQUEST", body.get("error").get("code").asString());
        assertEquals(0, transactionCountOf(referenceId));
        assertEquals(List.of(), entryTypesOf(referenceId));
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int transactionCountOf(UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference_type = 'CHARGE' AND reference_id = ?",
                Integer.class, referenceId);
    }

    private List<String> entryTypesOf(UUID referenceId) {
        return jdbcTemplate.queryForList("""
                SELECT e.entry_type FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE t.reference_type = 'CHARGE' AND t.reference_id = ?
                ORDER BY e.direction
                """, String.class, referenceId);
    }

    private String requestHashOf(UUID referenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_hash FROM ledger_transactions WHERE reference_type = 'CHARGE' AND reference_id = ?",
                String.class, referenceId);
    }

    private long walletBalance(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW' AND account_type = 'WALLET'",
                Long.class, walletId);
    }
}

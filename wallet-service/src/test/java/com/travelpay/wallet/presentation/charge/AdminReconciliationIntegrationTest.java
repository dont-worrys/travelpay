package com.travelpay.wallet.presentation.charge;

import com.travelpay.wallet.application.reconciliation.BankTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.LedgerTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 충전 대사 API를 실제 Postgres에서 검증한다. 은행·원장 조회는 대역(빈 목록)이다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "travelpay.wallet.charge.reconciliation.grace=5m",
        "travelpay.wallet.charge.reconciliation.overlap=10m",
        "travelpay.wallet.charge.reconciliation.max-period=1d",
        "travelpay.wallet.charge.reconciliation.running-timeout=30m"
})
class AdminReconciliationIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final String PATH = "/api/v1/admin/charges/reconciliations";
    private static final Instant INITIAL_FROM = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MICROS);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("travelpay.wallet.charge.reconciliation.initial-from", INITIAL_FROM::toString);
    }

    @MockitoBean
    private BankTransactionQueryClient bankClient;

    @MockitoBean
    private LedgerTransactionQueryClient ledgerClient;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Instant now;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".reconciliation_results, " + SCHEMA + ".reconciliation_runs CASCADE");
        when(bankClient.listTransactions(any(), any())).thenReturn(List.of());
        when(ledgerClient.listChargeTransactions(any(), any())).thenReturn(List.of());
        when(bankClient.findTransaction(anyString())).thenReturn(SourceLookup.notFound());
        when(ledgerClient.findChargeTransaction(any())).thenReturn(SourceLookup.notFound());
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void MASTER가_아니면_403() throws Exception {
        UUID runId = insertRun("COMPLETED", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofHours(1)));

        asUser(post(PATH)).andExpect(status().isForbidden());
        asUser(get(PATH)).andExpect(status().isForbidden());
        asUser(get(PATH + "/" + runId)).andExpect(status().isForbidden());
        assertEquals(1, runCount());
    }

    @Test
    void 기간_오류는_400() throws Exception {
        String from = now.minus(Duration.ofHours(2)).toString();
        String to = now.minus(Duration.ofHours(1)).toString();
        // 하나만 지정
        asMaster(post(PATH).param("from", from)).andExpect(status().isBadRequest());
        asMaster(post(PATH).param("to", to)).andExpect(status().isBadRequest());
        // from ≥ to
        asMaster(post(PATH).param("from", to).param("to", from)).andExpect(status().isBadRequest());
        asMaster(post(PATH).param("from", from).param("to", from)).andExpect(status().isBadRequest());
        // to > 지금 − 여유
        asMaster(post(PATH).param("from", from).param("to", now.minus(Duration.ofMinutes(1)).toString()))
                .andExpect(status().isBadRequest());
        // 최대 기간(1일) 초과
        asMaster(post(PATH).param("from", now.minus(Duration.ofDays(3)).toString()).param("to", to))
                .andExpect(status().isBadRequest());
        // 형식 오류
        asMaster(post(PATH).param("from", "2026-11-01").param("to", to)).andExpect(status().isBadRequest());
        assertEquals(0, runCount());
    }

    @Test
    void 실행_중이면_409() throws Exception {
        insertRun("RUNNING", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(1)));

        asMaster(post(PATH)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_ALREADY_RUNNING"));
        asMaster(post(PATH).param("from", now.minus(Duration.ofHours(2)).toString())
                .param("to", now.minus(Duration.ofHours(1)).toString()))
                .andExpect(status().isConflict());
        assertEquals(1, runCount());
    }

    @Test
    void 닫힌_기간이_없으면_409() throws Exception {
        insertRun("COMPLETED", now.minus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(10)),
                now.minus(Duration.ofMinutes(1)));

        asMaster(post(PATH)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_PERIOD_NOT_READY"));
    }

    @Test
    void 기간_생략_수동_실행은_스케줄러와_같은_기간_계산() throws Exception {
        asMaster(post(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.runType").value("AUTO"))
                .andExpect(jsonPath("$.data.periodFrom").value(INITIAL_FROM.toString()))
                .andExpect(jsonPath("$.data.overlapFrom").value(nullValue()))
                .andExpect(jsonPath("$.data.mismatchCount").value(0));
    }

    @Test
    void 기간_지정_수동_실행은_지난_기간을_재대사() throws Exception {
        Instant from = now.minus(Duration.ofDays(5));
        Instant to = now.minus(Duration.ofDays(4));

        asMaster(post(PATH).param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.runType").value("MANUAL"))
                .andExpect(jsonPath("$.data.periodFrom").value(from.toString()))
                .andExpect(jsonPath("$.data.periodTo").value(to.toString()))
                .andExpect(jsonPath("$.data.overlapFrom").value(nullValue()));
    }

    @Test
    void 실행_목록은_최근_시작순이고_status로_거름() throws Exception {
        UUID oldest = insertRun("COMPLETED", now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofHours(3)));
        UUID partial = insertRun("MANUAL", "PARTIAL", now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(2)));
        UUID failed = insertRun("FAILED", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofHours(1)));

        asMaster(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].runId",
                        contains(failed.toString(), partial.toString(), oldest.toString())))
                .andExpect(jsonPath("$.data.items[*].runType", contains("AUTO", "MANUAL", "AUTO")))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        asMaster(get(PATH).param("status", "PARTIAL"))
                .andExpect(jsonPath("$.data.items[*].runId", contains(partial.toString())));
        asMaster(get(PATH).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.data.items[*].runId", contains(partial.toString())))
                .andExpect(jsonPath("$.data.totalPages").value(3));
        asMaster(get(PATH).param("status", "NOPE")).andExpect(status().isBadRequest());
        asMaster(get(PATH).param("size", "0")).andExpect(status().isBadRequest());
        asMaster(get(PATH).param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void 실행_상세는_요약과_결과를_최근_감지순으로_유형별_필터() throws Exception {
        UUID runId = insertRun("MANUAL", "PARTIAL", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofHours(1)));
        UUID first = insertOrphan(runId, "BANK", "BANK_ORPHAN", now.minus(Duration.ofMinutes(30)));
        UUID second = insertChargeResult(runId, "SOURCE_UNAVAILABLE", now.minus(Duration.ofMinutes(20)));
        UUID third = insertOrphan(runId, "LEDGER", "LEDGER_ORPHAN", now.minus(Duration.ofMinutes(10)));
        UUID otherRun = insertRun("COMPLETED", now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofHours(3)));
        insertOrphan(otherRun, "BANK", "BANK_ORPHAN", now);

        asMaster(get(PATH + "/" + runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.run.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.run.runType").value("MANUAL"))
                .andExpect(jsonPath("$.data.results.items[*].resultId",
                        contains(third.toString(), second.toString(), first.toString())))
                .andExpect(jsonPath("$.data.results.totalElements").value(3));
        asMaster(get(PATH + "/" + runId).param("mismatchType", "SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.results.items[*].resultId", contains(second.toString())));
        asMaster(get(PATH + "/" + runId).param("mismatchType", "NOPE")).andExpect(status().isBadRequest());
        asMaster(get(PATH + "/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_RUN_NOT_FOUND"));
        asMaster(get(PATH + "/not-a-uuid")).andExpect(status().isBadRequest());
    }

    private UUID insertRun(String status, Instant from, Instant to, Instant startedAt) {
        return insertRun("AUTO", status, from, to, startedAt);
    }

    private UUID insertRun(String runType, String status, Instant from, Instant to, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reconciliation_runs (id, period_from, period_to, status, started_at, "
                        + "finished_at, run_type) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, Timestamp.from(from), Timestamp.from(to), status, Timestamp.from(startedAt),
                "RUNNING".equals(status) ? null : Timestamp.from(startedAt.plusSeconds(1)), runType);
        return id;
    }

    private UUID insertOrphan(UUID runId, String sourceType, String mismatchType, Instant detectedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reconciliation_results (id, run_id, source_type, source_key, mismatch_type, "
                        + "detail, snapshot, detected_at) VALUES (?, ?, ?, ?, ?, 'd', '{}'::jsonb, ?)",
                id, runId, sourceType, UUID.randomUUID().toString(), mismatchType, Timestamp.from(detectedAt));
        return id;
    }

    private UUID insertChargeResult(UUID runId, String mismatchType, Instant detectedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reconciliation_results (id, run_id, charge_id, mismatch_type, detail, "
                        + "snapshot, detected_at) VALUES (?, ?, ?, ?, 'd', '{}'::jsonb, ?)",
                id, runId, UUID.randomUUID(), mismatchType, Timestamp.from(detectedAt));
        return id;
    }

    private int runCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_runs", Integer.class);
    }

    private ResultActions asUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", UUID.randomUUID().toString()).header("User-Role", "USER"));
    }

    private ResultActions asMaster(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", UUID.randomUUID().toString()).header("User-Role", "MASTER"));
    }
}

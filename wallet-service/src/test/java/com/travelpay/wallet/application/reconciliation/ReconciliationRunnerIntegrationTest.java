package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationPeriod;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 대사 실행 경로(기간 계산·오래된 RUNNING 정리·조건부 종료)를 실제 Postgres에서 검증한다.
 * 스케줄러는 끄고 runScheduled()를 직접 부른다. 은행·원장 조회는 대역(빈 목록)이다. Docker가 없으면 건너뛴다.
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
class ReconciliationRunnerIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final Duration OVERLAP = Duration.ofMinutes(10);
    /** 직전 실행이 없을 때 시작점: 최대 기간(1일)보다 가깝게 둔다 */
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
    private ReconciliationRunner runner;

    @Autowired
    private ReconciliationPeriodCalculator calculator;

    @Autowired
    private ChargeReconciliationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Instant now;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".reconciliation_results, " + SCHEMA + ".reconciliation_runs CASCADE");
        when(bankClient.listTransactions(any(), any())).thenReturn(List.of());
        when(ledgerClient.listChargeTransactions(any(), any())).thenReturn(List.of());
        when(bankClient.findTransaction(anyString())).thenReturn(SourceLookup.notFound());
        when(ledgerClient.findChargeTransaction(any())).thenReturn(SourceLookup.notFound());
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void 직전_실행이_없으면_최초_시작점부터_지금_빼기_여유까지() {
        Instant before = Instant.now();
        ReconciliationRunResult result = runner.runScheduled().orElseThrow();
        Instant after = Instant.now();

        Map<String, Object> run = run(result.runId());
        assertEquals("COMPLETED", run.get("status"));
        assertEquals("AUTO", run.get("run_type"));
        assertEquals(INITIAL_FROM, instant(run, "period_from"));
        assertNull(run.get("overlap_from"));
        assertBetween(instant(run, "period_to"), before.minus(GRACE).truncatedTo(ChronoUnit.MICROS),
                after.minus(GRACE));
    }

    @Test
    void 직전_실행이_있으면_그_period_to_빼기_겹침부터_시작하고_overlap_from_기록() {
        Instant previousTo = now.minus(Duration.ofHours(2));
        insertRun("COMPLETED", previousTo.minus(Duration.ofHours(1)), previousTo, now.minus(Duration.ofHours(2)));

        ReconciliationRunResult result = runner.runScheduled().orElseThrow();

        Map<String, Object> run = run(result.runId());
        assertEquals(previousTo.minus(OVERLAP), instant(run, "period_from"));
        assertEquals(previousTo.minus(OVERLAP), instant(run, "overlap_from"));
    }

    @Test
    void 가장_최근에_시작한_실행이_과거_재대사여도_period_to가_늦은_실행이_기준() {
        Instant scheduledTo = now.minus(Duration.ofHours(1));
        insertRun("COMPLETED", scheduledTo.minus(Duration.ofHours(1)), scheduledTo, now.minus(Duration.ofHours(1)));
        // 나중에 시작한 지난 기간 재대사
        insertRun("COMPLETED", now.minus(Duration.ofDays(5)), now.minus(Duration.ofDays(4)), now.minus(Duration.ofMinutes(1)));

        ReconciliationPeriod period = calculator.next(now).orElseThrow();

        assertEquals(scheduledTo.minus(OVERLAP), period.from());
    }

    @Test
    void FAILED와_RUNNING은_기준에서_제외하고_PARTIAL은_포함() {
        insertRun("COMPLETED", now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(3)));
        Instant partialTo = now.minus(Duration.ofHours(2));
        insertRun("PARTIAL", now.minus(Duration.ofHours(3)), partialTo, now.minus(Duration.ofHours(2)));
        insertRun("FAILED", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)));
        insertRun("RUNNING", now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(1)));

        ReconciliationPeriod period = calculator.next(now).orElseThrow();

        assertEquals(partialTo.minus(OVERLAP), period.from());
        assertEquals(partialTo.minus(OVERLAP), period.overlapFrom());
    }

    @Test
    void 기간_지정_수동_실행은_스케줄러_위치를_움직이지_않음() {
        Instant autoTo = now.minus(Duration.ofDays(3));
        insertRun("COMPLETED", autoTo.minus(Duration.ofHours(1)), autoTo, autoTo);

        ReconciliationRunResult manual = runner.runManual(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
        assertEquals("MANUAL", run(manual.runId()).get("run_type"));
        assertEquals(ReconciliationRunStatus.COMPLETED, manual.status());

        ReconciliationRunResult next = runner.runScheduled().orElseThrow();

        Map<String, Object> nextRun = run(next.runId());
        assertEquals("AUTO", nextRun.get("run_type"));
        assertEquals(autoTo.minus(OVERLAP), instant(nextRun, "period_from"));
        assertEquals(autoTo.minus(OVERLAP), instant(nextRun, "overlap_from"));
    }

    @Test
    void 기간_생략_수동_실행은_AUTO라_위치를_움직임() {
        Instant autoTo = now.minus(Duration.ofHours(2));
        insertRun("COMPLETED", autoTo.minus(Duration.ofHours(1)), autoTo, autoTo);

        ReconciliationRunResult manual = runner.runManual(null, null);

        Map<String, Object> manualRun = run(manual.runId());
        assertEquals("AUTO", manualRun.get("run_type"));
        assertEquals(autoTo.minus(OVERLAP), instant(manualRun, "period_from"));
        Instant manualTo = instant(manualRun, "period_to");
        assertEquals(manualTo.minus(OVERLAP), calculator.next(Instant.now()).orElseThrow().from());
    }

    @Test
    void AUTO_실행_없이_기간_지정_실행만_있으면_최초_시작점부터() {
        insertRun("MANUAL", "COMPLETED", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(30)));
        insertRun("MANUAL", "PARTIAL", now.minus(Duration.ofDays(5)), now.minus(Duration.ofDays(4)),
                now.minus(Duration.ofMinutes(20)));

        ReconciliationPeriod period = calculator.next(now).orElseThrow();

        assertEquals(INITIAL_FROM, period.from());
        assertNull(period.overlapFrom());
        assertEquals(ReconciliationRunType.AUTO, period.runType());
    }

    @Test
    void to가_from_이하이면_실행하지_않음() {
        // 다음 from = (now + 10분) − 겹침(10분) = now ≥ 지금 − 여유(5분)
        insertRun("COMPLETED", now.minus(Duration.ofHours(1)), now.plus(OVERLAP), now.minus(Duration.ofMinutes(1)));

        Optional<ReconciliationRunResult> result = runner.runScheduled();

        assertTrue(result.isEmpty());
        assertEquals(1, runCount());
    }

    @Test
    void 직전_실행이_오래전이면_최대_기간만큼만_실행하고_다음_주기들이_따라잡음() {
        Instant previousTo = now.minus(Duration.ofDays(3));
        insertRun("COMPLETED", previousTo.minus(Duration.ofHours(1)), previousTo, previousTo);

        ReconciliationRunResult first = runner.runScheduled().orElseThrow();
        Map<String, Object> firstRun = run(first.runId());
        assertEquals(previousTo.minus(OVERLAP), instant(firstRun, "period_from"));
        assertEquals(previousTo.minus(OVERLAP).plus(Duration.ofDays(1)), instant(firstRun, "period_to"));

        Instant previousPeriodTo = instant(firstRun, "period_to");
        int runs = 1;
        while (true) {
            Instant before = Instant.now();
            ReconciliationRunResult next = runner.runScheduled().orElseThrow();
            runs++;
            Map<String, Object> run = run(next.runId());
            assertEquals(previousPeriodTo.minus(OVERLAP), instant(run, "period_from"));
            assertEquals(previousPeriodTo.minus(OVERLAP), instant(run, "overlap_from"));
            Instant periodTo = instant(run, "period_to");
            Duration length = Duration.between(instant(run, "period_from"), periodTo);
            assertFalse(length.compareTo(Duration.ofDays(1)) > 0);
            previousPeriodTo = periodTo;
            if (length.compareTo(Duration.ofDays(1)) < 0) {
                // 최대 기간보다 짧으면 지금 − 여유에 닿은 것
                assertBetween(periodTo, before.minus(GRACE).truncatedTo(ChronoUnit.MICROS),
                        Instant.now().minus(GRACE));
                break;
            }
            assertTrue(runs < 10, "따라잡지 못함");
        }
        // 3일 + 겹침을 1일씩: 1일, 1일, 1일 미만(지금 − 여유까지)
        assertEquals(4, runs);
    }

    @Test
    void 오래된_RUNNING은_FAILED로_정리하고_새_실행을_시작() {
        UUID stale = insertRun("RUNNING", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofHours(1)));

        ReconciliationRunResult result = runner.runScheduled().orElseThrow();

        Map<String, Object> staleRun = run(stale);
        assertEquals("FAILED", staleRun.get("status"));
        assertEquals(ReconciliationRunner.STALE_REASON, staleRun.get("failure_reason"));
        assertEquals(ReconciliationRunStatus.COMPLETED, result.status());
    }

    @Test
    void 만료되지_않은_RUNNING이_있으면_건너뜀() {
        UUID running = insertRun("RUNNING", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(5)));

        assertTrue(runner.runScheduled().isEmpty());
        assertEquals("RUNNING", run(running).get("status"));
        assertEquals(1, runCount());
    }

    @Test
    void 실행_도중_정리된_실행은_결과를_저장하지_않고_FAILED_유지() {
        when(bankClient.listTransactions(any(), any())).thenAnswer(invocation -> {
            cleanUpRunningAsStale();
            // 고아 결과가 생기는 거래
            return List.of(new BankTransactionRecord("orphan-" + UUID.randomUUID(), UUID.randomUUID(), "DEBIT",
                    "SUCCEEDED", 10_000));
        });

        ReconciliationRunResult result = service.run(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), null,
                ReconciliationRunType.MANUAL);

        assertEquals(ReconciliationRunStatus.FAILED, result.status());
        assertEquals("정리됨", result.failureReason());
        Map<String, Object> run = run(result.runId());
        assertEquals("FAILED", run.get("status"));
        assertEquals("정리됨", run.get("failure_reason"));
        assertEquals(0, resultCount(result.runId()));
    }

    @Test
    void 실행_도중_정리된_뒤_실패해도_정리된_사유를_덮어쓰지_않음() {
        when(bankClient.listTransactions(any(), any())).thenAnswer(invocation -> {
            cleanUpRunningAsStale();
            throw new ReconciliationSourceException("은행 거래 목록 조회 실패");
        });

        ReconciliationRunResult result = service.run(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), null,
                ReconciliationRunType.MANUAL);

        assertEquals(ReconciliationRunStatus.FAILED, result.status());
        assertEquals("정리됨", run(result.runId()).get("failure_reason"));
    }

    private void cleanUpRunningAsStale() {
        jdbcTemplate.update("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now(), "
                + "failure_reason = '정리됨' WHERE status = 'RUNNING'");
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

    private Map<String, Object> run(UUID runId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reconciliation_runs WHERE id = ?", runId);
    }

    private int runCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_runs", Integer.class);
    }

    private int resultCount(UUID runId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_results WHERE run_id = ?",
                Integer.class, runId);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    private static void assertBetween(Instant actual, Instant lower, Instant upper) {
        assertFalse(actual.isBefore(lower), actual + " < " + lower);
        assertFalse(actual.isAfter(upper), actual + " > " + upper);
    }
}

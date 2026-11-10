package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.reconciliation.ReconciliationAlreadyRunningException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실제 Postgres(Flyway 적용)에서 충전 대사 엔진을 검증한다. 은행·원장 조회 클라이언트는 테스트 대역이다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeReconciliationIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final long AMOUNT = 10_000;
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant IN_PERIOD = FROM.plus(Duration.ofHours(1));

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
    }

    @MockitoBean
    private BankTransactionQueryClient bankClient;

    @MockitoBean
    private LedgerTransactionQueryClient ledgerClient;

    @Autowired
    private ChargeReconciliationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID walletId;
    private final List<BankTransactionRecord> bankList = new ArrayList<>();
    private final List<LedgerTransactionRecord> ledgerList = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".reconciliation_results, " + SCHEMA + ".reconciliation_runs, "
                + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets CASCADE");
        UUID userId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        bankList.clear();
        ledgerList.clear();
        when(bankClient.listTransactions(any(), any())).thenReturn(bankList);
        when(ledgerClient.listChargeTransactions(any(), any())).thenReturn(ledgerList);
        when(bankClient.findTransaction(anyString())).thenReturn(SourceLookup.notFound());
        when(ledgerClient.findChargeTransaction(any())).thenReturn(SourceLookup.notFound());
    }

    @Test
    void 정상_충전만_있으면_어긋남_없이_COMPLETED() {
        Charge completed = completedCharge();
        bankList.add(debit(completed, "SUCCEEDED", AMOUNT));
        ledgerList.add(posted(completed, AMOUNT, "KRW"));

        Charge failed = charge("FAILED", null, null, null);
        bankList.add(new BankTransactionRecord(failed.bankRequestId(), null, "DEBIT", "FAILED", AMOUNT));

        Charge refunded = charge("REFUNDED", UUID.randomUUID(), UUID.randomUUID(), null);
        bankList.add(debit(refunded, "SUCCEEDED", AMOUNT));
        bankList.add(refund(refunded, "SUCCEEDED", AMOUNT));
        ledgerList.add(rejected(refunded));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(ReconciliationRunStatus.COMPLETED, result.status());
        assertCounts(result, 3, 3, 0, 0);
        assertEquals(0, resultCount(result.runId()));
        assertRunRow(result.runId(), "COMPLETED");
        verify(bankClient, never()).findTransaction(anyString());
        verify(ledgerClient, never()).findChargeTransaction(any());
    }

    @Test
    void FAILED인데_출금_성공이_있으면_BANK_DEBIT_WITHOUT_CREDIT() {
        Charge failed = charge("FAILED", null, null, null);
        bankList.add(new BankTransactionRecord(failed.bankRequestId(), UUID.randomUUID(), "DEBIT", "SUCCEEDED",
                AMOUNT));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        // 출금 성공 기록이 있는데 bank_tx_id가 비어 있으므로 ID 불일치도 함께
        assertEquals(List.of("BANK_DEBIT_WITHOUT_CREDIT", "STATUS_MISMATCH"), types(result.runId(), failed.id()));
        assertTrue(detail(result.runId(), failed.id(), "STATUS_MISMATCH").startsWith("저장된 ID와 다름: bank_tx_id"));
        assertCounts(result, 1, 0, 1, 0);
    }

    @Test
    void COMPLETED인데_출금_성공이_없으면_단건_재조회_후_CREDIT_WITHOUT_BANK_DEBIT() {
        Charge completed = charge("COMPLETED", null, null, UUID.randomUUID());
        ledgerList.add(posted(completed, AMOUNT, "KRW"));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(List.of("CREDIT_WITHOUT_BANK_DEBIT"), types(result.runId(), completed.id()));
        verify(bankClient, times(1)).findTransaction(completed.bankRequestId());
    }

    @Test
    void COMPLETED인데_환불_성공이_있으면_CREDIT_AND_REFUND_BOTH() {
        Charge completed = completedCharge();
        bankList.add(debit(completed, "SUCCEEDED", AMOUNT));
        ledgerList.add(posted(completed, AMOUNT, "KRW"));
        bankList.add(refund(completed, "SUCCEEDED", AMOUNT));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        List<String> types = types(result.runId(), completed.id());
        assertTrue(types.contains("CREDIT_AND_REFUND_BOTH"), types.toString());
    }

    @Test
    void 금액이_충전_금액과_다르면_AMOUNT_MISMATCH와_어긋난_비교를_기록() {
        Charge debitDiffers = completedCharge();
        bankList.add(debit(debitDiffers, "SUCCEEDED", AMOUNT - 1));
        ledgerList.add(posted(debitDiffers, AMOUNT, "KRW"));

        Charge ledgerDiffers = completedCharge();
        bankList.add(debit(ledgerDiffers, "SUCCEEDED", AMOUNT));
        ledgerList.add(posted(ledgerDiffers, AMOUNT + 1, "JPY"));

        Charge refundDiffers = charge("REFUNDED", UUID.randomUUID(), UUID.randomUUID(), null);
        bankList.add(debit(refundDiffers, "SUCCEEDED", AMOUNT));
        bankList.add(refund(refundDiffers, "SUCCEEDED", AMOUNT - 2));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertTrue(detail(result.runId(), debitDiffers.id(), "AMOUNT_MISMATCH").contains("은행 출금 금액(9999)"));
        String ledgerDetail = detail(result.runId(), ledgerDiffers.id(), "AMOUNT_MISMATCH");
        assertTrue(ledgerDetail.contains("원장 적립 금액(10001)"), ledgerDetail);
        assertTrue(ledgerDetail.contains("원장 적립 통화(JPY)"), ledgerDetail);
        assertTrue(detail(result.runId(), refundDiffers.id(), "AMOUNT_MISMATCH").contains("은행 환불 금액(9998)"));
        assertCounts(result, 3, 0, 3, 0);
    }

    @Test
    void FAILED인데_원장_거래가_있으면_상태_STATUS_MISMATCH() {
        Charge failed = charge("FAILED", null, null, null);
        ledgerList.add(rejected(failed));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(List.of("STATUS_MISMATCH"), types(result.runId(), failed.id()));
        assertTrue(detail(result.runId(), failed.id(), "STATUS_MISMATCH").startsWith("상태가 사실과 다름"));
    }

    @Test
    void 저장된_ID가_찾은_기록과_다르면_ID_STATUS_MISMATCH와_양쪽_ID를_스냅샷에() {
        Charge completed = completedCharge();
        UUID actualBankTxId = UUID.randomUUID();
        bankList.add(new BankTransactionRecord(completed.bankRequestId(), actualBankTxId, "DEBIT", "SUCCEEDED",
                AMOUNT));
        ledgerList.add(posted(completed, AMOUNT, "KRW"));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(List.of("STATUS_MISMATCH"), types(result.runId(), completed.id()));
        assertTrue(detail(result.runId(), completed.id(), "STATUS_MISMATCH").startsWith("저장된 ID와 다름: bank_tx_id"));
        String snapshot = snapshot(result.runId(), completed.id(), "STATUS_MISMATCH");
        assertTrue(snapshot.contains(actualBankTxId.toString()), snapshot);
        assertTrue(snapshot.contains(completed.bankTxId().toString()), snapshot);
        assertTrue(snapshot.contains(completed.ledgerRef().toString()), snapshot);
    }

    @Test
    void 대응하는_충전이_없는_은행_거래와_원장_거래는_고아() {
        BankTransactionRecord orphanBank = new BankTransactionRecord(UUID.randomUUID().toString(), UUID.randomUUID(),
                "DEBIT", "SUCCEEDED", AMOUNT);
        bankList.add(orphanBank);
        LedgerTransactionRecord orphanLedger = new LedgerTransactionRecord(UUID.randomUUID(), "POSTED",
                UUID.randomUUID(), List.of());
        ledgerList.add(orphanLedger);

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT source_type, source_key, mismatch_type, charge_id FROM reconciliation_results
                 WHERE run_id = ? ORDER BY mismatch_type
                """, result.runId());
        assertEquals(2, rows.size());
        assertEquals(Map.of("source_type", "BANK", "source_key", orphanBank.requestId(),
                "mismatch_type", "BANK_ORPHAN"), withoutCharge(rows.get(0)));
        assertEquals(Map.of("source_type", "LEDGER", "source_key", orphanLedger.transactionId().toString(),
                "mismatch_type", "LEDGER_ORPHAN"), withoutCharge(rows.get(1)));
        assertCounts(result, 0, 0, 2, 0);
    }

    @Test
    void MANUAL_REVIEW는_판정_없이_REVIEW_REQUIRED와_스냅샷() {
        Charge review = charge("MANUAL_REVIEW", UUID.randomUUID(), null, null);
        bankList.add(debit(review, "SUCCEEDED", AMOUNT));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(List.of("REVIEW_REQUIRED"), types(result.runId(), review.id()));
        String snapshot = snapshot(result.runId(), review.id(), "REVIEW_REQUIRED");
        assertTrue(snapshot.contains(review.bankTxId().toString()), snapshot);
        verify(bankClient, never()).findTransaction(anyString());
    }

    @Test
    void 처리_중_충전은_판정에서_제외() {
        for (String status : List.of("REQUESTED", "BANK_PROCESSING", "CREDIT_PROCESSING", "REFUND_PROCESSING")) {
            Charge inProgress = charge(status, null, null, null);
            bankList.add(debit(inProgress, "SUCCEEDED", AMOUNT));
        }

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(ReconciliationRunStatus.COMPLETED, result.status());
        assertCounts(result, 0, 0, 0, 0);
        assertEquals(0, resultCount(result.runId()));
    }

    @Test
    void 원장_적립이_기간_밖에_찍혀도_단건_재조회로_정상_판정() {
        Charge completed = completedCharge();
        bankList.add(debit(completed, "SUCCEEDED", AMOUNT));
        when(ledgerClient.findChargeTransaction(completed.id()))
                .thenReturn(SourceLookup.found(posted(completed, AMOUNT, "KRW")));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertCounts(result, 1, 1, 0, 0);
        assertEquals(0, resultCount(result.runId()));
        verify(ledgerClient, times(1)).findChargeTransaction(completed.id());
    }

    @Test
    void 기간_밖에_접수된_충전도_목록_키로_찾아_판정() {
        Charge before = charge("COMPLETED", UUID.randomUUID(), null, UUID.randomUUID(), FROM.minusSeconds(60));
        bankList.add(debit(before, "SUCCEEDED", AMOUNT));
        bankList.add(refund(before, "SUCCEEDED", AMOUNT));
        when(ledgerClient.findChargeTransaction(before.id()))
                .thenReturn(SourceLookup.found(posted(before, AMOUNT, "KRW")));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertTrue(types(result.runId(), before.id()).contains("CREDIT_AND_REFUND_BOTH"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation_results WHERE run_id = ? AND charge_id IS NULL",
                Integer.class, result.runId()));
    }

    @Test
    void 단건_재조회_실패면_SOURCE_UNAVAILABLE과_확인된_어긋남을_함께_남기고_PARTIAL() {
        Charge withMismatch = completedCharge();
        bankList.add(debit(withMismatch, "SUCCEEDED", AMOUNT));
        bankList.add(refund(withMismatch, "SUCCEEDED", AMOUNT));
        when(ledgerClient.findChargeTransaction(withMismatch.id()))
                .thenReturn(SourceLookup.unavailable("feign.RetryableException status=-1"));

        Charge onlyUnavailable = completedCharge();
        ledgerList.add(posted(onlyUnavailable, AMOUNT, "KRW"));
        when(bankClient.findTransaction(onlyUnavailable.bankRequestId()))
                .thenReturn(SourceLookup.unavailable("feign.FeignException$ServiceUnavailable status=503"));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(ReconciliationRunStatus.PARTIAL, result.status());
        assertRunRow(result.runId(), "PARTIAL");
        List<String> types = types(result.runId(), withMismatch.id());
        assertTrue(types.containsAll(List.of("SOURCE_UNAVAILABLE", "CREDIT_AND_REFUND_BOTH")), types.toString());
        assertTrue(!types.contains("BANK_DEBIT_WITHOUT_CREDIT"), "원장 규칙은 보류되어야 함: " + types);
        String unavailable = detail(result.runId(), withMismatch.id(), "SOURCE_UNAVAILABLE");
        assertTrue(unavailable.startsWith("원장 조회 실패(feign.RetryableException"), unavailable);
        String snapshot = snapshot(result.runId(), withMismatch.id(), "SOURCE_UNAVAILABLE");
        assertTrue(snapshot.contains("\"lookup\": \"UNAVAILABLE\""), snapshot);
        assertTrue(snapshot.contains(withMismatch.bankTxId().toString()), snapshot);

        assertEquals(List.of("SOURCE_UNAVAILABLE"), types(result.runId(), onlyUnavailable.id()));
        assertTrue(detail(result.runId(), onlyUnavailable.id(), "SOURCE_UNAVAILABLE").startsWith("은행 출금 조회 실패"));
        // 두 충전 모두 unverifiable, 어긋남이 함께 있는 충전은 mismatch에도, SOURCE_UNAVAILABLE만 있는 충전은 matched가 아님
        assertCounts(result, 2, 0, 1, 2);
    }

    @Test
    void 기간_목록_수집_실패면_FAILED이고_결과는_저장하지_않음() {
        Charge failed = charge("FAILED", null, null, null);
        bankList.add(debit(failed, "SUCCEEDED", AMOUNT));
        when(ledgerClient.listChargeTransactions(any(), any()))
                .thenThrow(new ReconciliationSourceException("원장 거래 목록 조회 실패 page=1: x"));

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertEquals(ReconciliationRunStatus.FAILED, result.status());
        Map<String, Object> run = runRow(result.runId());
        assertEquals("FAILED", run.get("status"));
        assertEquals("원장 거래 목록 조회 실패 page=1: x", run.get("failure_reason"));
        assertNotNull(run.get("finished_at"));
        assertEquals(0, resultCount(result.runId()));
    }

    @Test
    void 예상하지_못한_오류도_실행을_FAILED로_끝내고_다시_던짐() {
        when(bankClient.listTransactions(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> service.run(FROM, TO, null, ReconciliationRunType.MANUAL));

        Map<String, Object> run = jdbcTemplate.queryForMap("SELECT status, failure_reason FROM reconciliation_runs");
        assertEquals("FAILED", run.get("status"));
        assertEquals("대사 처리 중 오류: java.lang.IllegalStateException", run.get("failure_reason"));
    }

    @Test
    void RUNNING_실행이_있으면_새_실행을_거절() {
        jdbcTemplate.update("""
                INSERT INTO reconciliation_runs (id, period_from, period_to, status, started_at, run_type)
                VALUES (?, ?::timestamptz, ?::timestamptz, 'RUNNING', now(), 'AUTO')
                """, UUID.randomUUID(), FROM.toString(), TO.toString());

        assertThrows(ReconciliationAlreadyRunningException.class,
                () -> service.run(FROM, TO, null, ReconciliationRunType.MANUAL));

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_runs", Integer.class));
        verify(bankClient, never()).listTransactions(any(), any());
    }

    @Test
    void 같은_기간_재실행은_새_실행_기록을_만들고_이전_결과는_그대로() {
        Charge failed = charge("FAILED", null, null, null);
        bankList.add(debit(failed, "SUCCEEDED", AMOUNT));

        ReconciliationRunResult first = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);
        List<Map<String, Object>> firstRows = resultRows(first.runId());

        ReconciliationRunResult second = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertTrue(!first.runId().equals(second.runId()));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_runs", Integer.class));
        assertEquals(firstRows, resultRows(first.runId()));
        assertEquals(firstRows.size(), resultRows(second.runId()).size());
    }

    // ---- 준비 ----

    private record Charge(UUID id, String bankRequestId, String refundRequestId, UUID bankTxId,
                          UUID refundBankTxId, UUID ledgerRef) {
    }

    private Charge completedCharge() {
        return charge("COMPLETED", UUID.randomUUID(), null, UUID.randomUUID());
    }

    private Charge charge(String status, UUID bankTxId, UUID refundBankTxId, UUID ledgerRef) {
        return charge(status, bankTxId, refundBankTxId, ledgerRef, IN_PERIOD);
    }

    private Charge charge(String status, UUID bankTxId, UUID refundBankTxId, UUID ledgerRef, Instant createdAt) {
        Charge charge = new Charge(UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                bankTxId, refundBankTxId, ledgerRef);
        jdbcTemplate.update("""
                INSERT INTO charge_requests (id, user_id, wallet_id, amount, currency, bank_code, account_number_enc,
                    holder_name_snapshot, status, idempotency_key, bank_request_id, refund_request_id,
                    bank_tx_id, refund_bank_tx_id, ledger_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'KRW', '004', 'enc', '홍길동', ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, charge.id(), UUID.randomUUID(), walletId, AMOUNT, status, UUID.randomUUID(),
                charge.bankRequestId(), charge.refundRequestId(), bankTxId, refundBankTxId, ledgerRef,
                createdAt.toString(), createdAt.toString());
        return charge;
    }

    private static BankTransactionRecord debit(Charge charge, String status, long amount) {
        return new BankTransactionRecord(charge.bankRequestId(),
                "SUCCEEDED".equals(status) ? charge.bankTxId() : null, "DEBIT", status, amount);
    }

    private static BankTransactionRecord refund(Charge charge, String status, long amount) {
        return new BankTransactionRecord(charge.refundRequestId(),
                "SUCCEEDED".equals(status) ? charge.refundBankTxId() : null, "REFUND", status, amount);
    }

    private LedgerTransactionRecord posted(Charge charge, long amount, String currency) {
        UUID transactionId = charge.ledgerRef() != null ? charge.ledgerRef() : UUID.randomUUID();
        return new LedgerTransactionRecord(transactionId, "POSTED", charge.id(), List.of(
                new LedgerEntryRecord("SYSTEM", null, "DEBIT", currency, amount, "CHARGE_OUT"),
                new LedgerEntryRecord("USER", walletId, "CREDIT", currency, amount, "CHARGE_IN")));
    }

    private static LedgerTransactionRecord rejected(Charge charge) {
        return new LedgerTransactionRecord(UUID.randomUUID(), "REJECTED", charge.id(), List.of());
    }

    // ---- 확인 ----

    private static void assertCounts(ReconciliationRunResult result, int target, int matched, int mismatch,
                                     int unverifiable) {
        assertEquals(List.of(target, matched, mismatch, unverifiable), List.of(result.targetCount(),
                result.matchedCount(), result.mismatchCount(), result.unverifiableCount()), "target·matched·mismatch·unverifiable");
    }

    private void assertRunRow(UUID runId, String status) {
        Map<String, Object> run = runRow(runId);
        assertEquals(status, run.get("status"));
        assertNotNull(run.get("finished_at"));
        assertNull(run.get("failure_reason"));
    }

    private Map<String, Object> runRow(UUID runId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reconciliation_runs WHERE id = ?", runId);
    }

    private int resultCount(UUID runId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_results WHERE run_id = ?",
                Integer.class, runId);
    }

    private List<String> types(UUID runId, UUID chargeId) {
        return jdbcTemplate.queryForList("""
                SELECT mismatch_type FROM reconciliation_results
                 WHERE run_id = ? AND charge_id = ? ORDER BY mismatch_type
                """, String.class, runId, chargeId);
    }

    private String detail(UUID runId, UUID chargeId, String type) {
        return jdbcTemplate.queryForObject("""
                SELECT detail FROM reconciliation_results WHERE run_id = ? AND charge_id = ? AND mismatch_type = ?
                """, String.class, runId, chargeId, type);
    }

    private String snapshot(UUID runId, UUID chargeId, String type) {
        return jdbcTemplate.queryForObject("""
                SELECT snapshot::text FROM reconciliation_results
                 WHERE run_id = ? AND charge_id = ? AND mismatch_type = ?
                """, String.class, runId, chargeId, type);
    }

    private List<Map<String, Object>> resultRows(UUID runId) {
        return jdbcTemplate.queryForList("""
                SELECT id, charge_id, mismatch_type, detail, snapshot::text AS snapshot, detected_at, resolution_status
                  FROM reconciliation_results WHERE run_id = ? ORDER BY mismatch_type
                """, runId);
    }

    private static Map<String, Object> withoutCharge(Map<String, Object> row) {
        assertNull(row.get("charge_id"));
        return Map.of("source_type", row.get("source_type"), "source_key", row.get("source_key"),
                "mismatch_type", row.get("mismatch_type"));
    }
}

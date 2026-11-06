package com.travelpay.wallet.application.reconciliation;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunStatus;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunType;
import com.travelpay.wallet.infra.client.FeignFailures;
import com.travelpay.wallet.infra.client.bank.BankFeignClient;
import com.travelpay.wallet.infra.client.bank.BankTransactionDetailResponse;
import com.travelpay.wallet.infra.client.ledger.LedgerTransactionDetailResponse;
import com.travelpay.wallet.infra.client.ledger.LedgerTransactionDetailResponse.Entry;
import com.travelpay.wallet.infra.client.ledger.LedgerTransactionFeignClient;
import com.travelpay.wallet.infra.client.ledger.LedgerTransactionPeriodResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 충전 대사 측정: 탐지율·오탐, 경계, 조회 실패, 1만 건 소요 시간.
 * 실제 Postgres + 실제 조회 어댑터(Bank/LedgerTransactionQueryAdapter). Feign 인터페이스만 대역으로 바꿔
 * 은행(page·size)·원장(cursor·size) 페이지네이션과 404/503 분류를 운영 어댑터가 그대로 처리하게 한다.
 * 판정이 기대와 달라도 데이터·기대값을 맞추지 않고 집계해 출력한 뒤 assertAll로 실패를 남긴다.
 * 실행: ./gradlew :wallet-service:performanceTest
 */
@Tag("performance")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeReconciliationMeasurementTest {

    private static final String SCHEMA = "wallet_service";
    private static final long AMOUNT = 10_000;
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");
    private static final Duration DAY = Duration.between(FROM, TO);
    private static final String SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";

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
    private BankFeignClient bankFeign;

    @MockitoBean
    private LedgerTransactionFeignClient ledgerFeign;

    @Autowired
    private ChargeReconciliationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID walletId;
    private Sources sources;
    private List<Object[]> chargeRows;
    private int seq;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".reconciliation_results, " + SCHEMA + ".reconciliation_runs, "
                + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets CASCADE");
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId,
                UUID.randomUUID());
        sources = new Sources();
        chargeRows = new ArrayList<>();
        seq = 0;
        when(bankFeign.listTransactions(any(), any(), anyInt(), anyInt())).thenAnswer(inv ->
                sources.bankPage(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        when(bankFeign.findTransaction(anyString())).thenAnswer(inv -> sources.bankSingle(inv.getArgument(0)));
        when(ledgerFeign.listByPeriod(eq("CHARGE"), any(), any(), any(), anyInt())).thenAnswer(inv ->
                sources.ledgerPage(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        when(ledgerFeign.findByReference(eq("CHARGE"), any())).thenAnswer(inv ->
                sources.ledgerSingle(inv.getArgument(1)));
    }

    // ================= 1. 탐지율·오탐 =================

    @Test
    void 탐지율과_오탐() {
        List<Injection> injections = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            injections.add(bankDebitWithoutCredit(i));
            injections.add(creditWithoutBankDebit(i));
            injections.add(creditAndRefundBoth(i));
            injections.add(amountMismatch(i));
            injections.add(statusMismatch(i));
            injections.add(bankOrphan(i));
            injections.add(ledgerOrphan(i));
            injections.add(reviewRequired(i));
        }
        Set<String> normalKeys = normalCharges(1_000);
        flushCharges();

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        Map<String, List<String>> rows = resultRowsByKey(result.runId());
        int unavailableRows = 0;
        int anomalyRows = 0;
        for (List<String> types : rows.values()) {
            for (String type : types) {
                if (SOURCE_UNAVAILABLE.equals(type)) {
                    unavailableRows++;
                } else {
                    anomalyRows++;
                }
            }
        }

        Map<String, TypeStat> byType = new LinkedHashMap<>();
        Map<String, VariantStat> byVariant = new LinkedHashMap<>();
        Set<String> injectedKeys = new HashSet<>();
        int hitCases = 0;
        int matchingRows = 0;
        for (Injection inj : injections) {
            injectedKeys.add(inj.key());
            List<String> got = withoutUnavailable(rows.getOrDefault(inj.key(), List.of()));
            long expected = got.stream().filter(inj.type()::equals).count();
            List<String> others = got.stream().filter(t -> !t.equals(inj.type())).toList();
            TypeStat ts = byType.computeIfAbsent(inj.type(), k -> new TypeStat());
            VariantStat vs = byVariant.computeIfAbsent(inj.type() + " " + inj.variant(),
                    k -> new VariantStat(inj.type(), inj.variant(), inj.condition(), inj.basis()));
            ts.injected++;
            vs.injected++;
            if (expected >= 1) {
                ts.hit++;
                vs.hit++;
                hitCases++;
            }
            matchingRows += (int) expected;
            ts.duplicates += (int) Math.max(0, expected - 1);
            ts.otherRows += others.size();
            ts.otherTypes.addAll(others);
            vs.actual.add(got.isEmpty() ? "(없음)" : String.join("+", got));
        }

        int normalHitCharges = 0;
        int normalRows = 0;
        for (String key : normalKeys) {
            List<String> got = withoutUnavailable(rows.getOrDefault(key, List.of()));
            if (!got.isEmpty()) {
                normalHitCharges++;
                normalRows += got.size();
            }
        }
        List<String> unattributed = new ArrayList<>();
        rows.forEach((key, types) -> {
            if (!injectedKeys.contains(key) && !normalKeys.contains(key)) {
                unattributed.add(key + "=" + types);
            }
        });

        double recall = hitCases / 80.0;
        double precision = anomalyRows == 0 ? 0 : (double) matchingRows / anomalyRows;
        double normalFpRate = normalHitCharges / 1_000.0;

        StringBuilder out = new StringBuilder("\n=== 1. 탐지율·오탐 (이상 80 + 정상 1,000, 한 실행) ===\n");
        out.append(String.format("run status=%s target=%d matched=%d mismatch=%d unverifiable=%d%n",
                result.status(), result.targetCount(), result.matchedCount(), result.mismatchCount(),
                result.unverifiableCount()));
        out.append("| 유형 | 넣은 건수 | 기대 유형으로 잡은 건수 | 다른 유형 결과 행 | 기대 유형 중복 행 |\n");
        byType.forEach((type, s) -> out.append(String.format("| %s | %d | %d | %d%s | %d |%n", type, s.injected,
                s.hit, s.otherRows, s.otherTypes.isEmpty() ? "" : " " + s.otherTypes, s.duplicates)));
        out.append("\n| 변형 | 검증 조건 | 근거 판정 조건 | 넣은 | 잡은 | 실제 결과 유형 |\n");
        byVariant.forEach((k, v) -> out.append(String.format("| %s %s | %s | %s | %d | %d | %s |%n", v.type,
                v.variant, v.condition, v.basis, v.injected, v.hit, v.actual)));
        out.append(String.format("%n재현율(건) = %d / 80 = %.4f%n", hitCases, recall));
        out.append(String.format("정밀도(행) = %d / %d = %.4f  (SOURCE_UNAVAILABLE 제외)%n", matchingRows, anomalyRows,
                precision));
        out.append(String.format("정상 오탐률 = %d / 1000 = %.4f, 정상 건 이상 결과 행 = %d%n", normalHitCharges,
                normalFpRate, normalRows));
        out.append(String.format("SOURCE_UNAVAILABLE 행 = %d, 귀속 불명 행 = %d %s%n", unavailableRows,
                unattributed.size(), unattributed));
        out.append(sources.pagingSummary());
        System.out.println(out);

        final int hits = hitCases;
        final int matching = matchingRows;
        final int anomalies = anomalyRows;
        final int normalHits = normalHitCharges;
        final int unavailable = unavailableRows;
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(80, hits, "재현율 분자"));
        checks.add(() -> assertEquals(anomalies, matching, "정밀도: 기대 외 결과 행 존재"));
        checks.add(() -> assertEquals(0, normalHits, "정상 충전 오탐"));
        checks.add(() -> assertEquals(0, unavailable, "SOURCE_UNAVAILABLE"));
        checks.add(() -> assertEquals(List.of(), unattributed, "귀속 불명 행"));
        checks.addAll(sources.pagingChecks());
        assertAll(checks);
    }

    // ================= 2. 경계 =================

    @Test
    void 경계_기간_밖_원장과_처리_중_충전() {
        Set<String> normalKeys = normalCharges(100);
        List<String> outsideKeys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Charge c = charge("COMPLETED", true, false, true);
            debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
            // 원장 적립 시각이 기간 밖 → 기간 목록에는 없고 단건 조회로만 확인
            ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", walletId), TO.plus(Duration.ofMinutes(5)));
            outsideKeys.add(key(c));
        }
        List<String> progressKeys = new ArrayList<>();
        for (String status : List.of("REQUESTED", "BANK_PROCESSING", "CREDIT_PROCESSING", "REFUND_PROCESSING")) {
            for (int i = 0; i < 5; i++) {
                Charge c = charge(status, true, false, false);
                debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
                progressKeys.add(key(c));
            }
        }
        flushCharges();

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);
        Map<String, List<String>> rows = resultRowsByKey(result.runId());

        long outsideRows = outsideKeys.stream().mapToLong(k -> rows.getOrDefault(k, List.of()).size()).sum();
        long progressRows = progressKeys.stream().mapToLong(k -> rows.getOrDefault(k, List.of()).size()).sum();
        long normalRows = normalKeys.stream().mapToLong(k -> rows.getOrDefault(k, List.of()).size()).sum();
        int total = rows.values().stream().mapToInt(List::size).sum();
        System.out.printf("%n=== 2. 경계 (정상 100 + 기간 밖 원장 10 + 처리 중 20) ===%n"
                        + "run status=%s target=%d matched=%d mismatch=%d unverifiable=%d%n"
                        + "| 구분 | 건수 | 결과 행 |%n| 기간 밖 원장 적립 | 10 | %d |%n| 처리 중 | 20 | %d |%n"
                        + "| 정상 | 100 | %d |%n전체 결과 행 = %d %s, 원장 단건 조회 = %d회%n%s",
                result.status(), result.targetCount(), result.matchedCount(), result.mismatchCount(),
                result.unverifiableCount(), outsideRows, progressRows, normalRows, total, rows,
                sources.ledgerSingleCalls.get(), sources.pagingSummary());

        assertAll(
                () -> assertEquals(ReconciliationRunStatus.COMPLETED, result.status()),
                () -> assertEquals(0, total, "결과 행"),
                () -> assertEquals(110, result.targetCount(), "target"),
                () -> assertEquals(110, result.matchedCount(), "matched"),
                () -> assertEquals(10, sources.ledgerSingleCalls.get(), "원장 단건 조회 횟수"));
    }

    // ================= 3. 조회 실패 =================

    @Test
    void 단건_조회_실패는_해당_건만_SOURCE_UNAVAILABLE() {
        Set<String> normalKeys = normalCharges(100);
        List<String> failKeys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Charge ledgerFail = charge("COMPLETED", true, false, true);
            debit(ledgerFail, "SUCCEEDED", AMOUNT, ledgerFail.bankTxId);
            sources.ledgerUnavailable.add(ledgerFail.id);
            failKeys.add(key(ledgerFail));

            Charge debitFail = charge("COMPLETED", true, false, true);
            ledger(debitFail, debitFail.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", walletId), at());
            sources.bankUnavailable.add(debitFail.bankRequestId);
            failKeys.add(key(debitFail));

            Charge refundFail = charge("REFUNDED", true, true, false);
            debit(refundFail, "SUCCEEDED", AMOUNT, refundFail.bankTxId);
            sources.bankUnavailable.add(refundFail.refundRequestId);
            failKeys.add(key(refundFail));
        }
        flushCharges();

        ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);
        Map<String, List<String>> rows = resultRowsByKey(result.runId());

        int exact = 0;
        List<String> wrong = new ArrayList<>();
        for (String k : failKeys) {
            List<String> got = rows.getOrDefault(k, List.of());
            if (got.equals(List.of(SOURCE_UNAVAILABLE))) {
                exact++;
            } else {
                wrong.add(k + "=" + got);
            }
        }
        Set<String> failSet = new HashSet<>(failKeys);
        List<String> outside = new ArrayList<>();
        rows.forEach((k, types) -> {
            if (!failSet.contains(k)) {
                outside.add(k + "=" + types + (normalKeys.contains(k) ? "(정상)" : ""));
            }
        });
        final int exactCount = exact;
        System.out.printf("%n=== 3. 조회 실패 (정상 100 + 단건 503 30) ===%n"
                        + "run status=%s target=%d matched=%d mismatch=%d unverifiable=%d%n"
                        + "| 구분 | 건수 | SOURCE_UNAVAILABLE만 나온 건 | 다른 결과 |%n"
                        + "| 단건 Unavailable | 30 | %d | %s |%n| 그 외(정상) | 100 | - | %s |%n%s",
                result.status(), result.targetCount(), result.matchedCount(), result.mismatchCount(),
                result.unverifiableCount(), exact, wrong, outside, sources.pagingSummary());

        assertAll(
                () -> assertEquals(ReconciliationRunStatus.PARTIAL, result.status()),
                () -> assertEquals(30, exactCount, "SOURCE_UNAVAILABLE만 나온 건: " + wrong),
                () -> assertEquals(List.of(), outside, "실패 건 외 결과"),
                () -> assertEquals(30, result.unverifiableCount(), "unverifiable"),
                () -> assertEquals(0, result.mismatchCount(), "mismatch"));
    }

    // ================= 4. 소요 시간 =================

    @Test
    void 충전_1만_건_소요_시간() {
        normalChargesSpread(10_000);
        flushCharges();

        List<Long> millis = new ArrayList<>();
        List<String> runSummaries = new ArrayList<>();
        for (int round = 0; round <= 5; round++) {
            // 실행·결과만 정리, 충전 원본은 유지. 결과를 참조하는 FK 사슬이 있어 TRUNCATE 대신 DELETE
            jdbcTemplate.update("DELETE FROM reconciliation_results");
            jdbcTemplate.update("DELETE FROM reconciliation_runs");
            long start = System.nanoTime();
            ReconciliationRunResult result = service.run(FROM, TO, null, ReconciliationRunType.MANUAL);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            int rows = jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_results WHERE run_id = ?",
                    Integer.class, result.runId());
            runSummaries.add(String.format("%s %s target=%d matched=%d rows=%d %dms", round == 0 ? "준비" : "#" + round,
                    result.status(), result.targetCount(), result.matchedCount(), rows, elapsed));
            if (round > 0) {
                millis.add(elapsed);
            }
        }
        List<Long> sorted = millis.stream().sorted().toList();
        System.out.printf("%n=== 4. 소요 시간 (정상 1만 건, 1일 균등, overlapFrom=null, MANUAL) ===%n"
                        + "%s%n5회(ms) = %s, 중앙값 = %dms%n%s",
                String.join("\n", runSummaries), millis, sorted.get(2), sources.pagingSummary());
        assertAll(runSummaries.stream().<Executable>map(s ->
                () -> assertEquals(true, s.contains("COMPLETED target=10000 matched=10000 rows=0 "), s)));
    }

    // ================= 이상 변형 =================

    private Injection bankDebitWithoutCredit(int i) {
        String type = "BANK_DEBIT_WITHOUT_CREDIT";
        switch (i % 4) {
            case 0 -> {
                Charge c = charge("COMPLETED", true, false, false);
                debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
                ledger(c, UUID.randomUUID(), "REJECTED", List.of(), at());
                return inj(type, "①", c, "COMPLETED·출금 성공·원장 REJECTED(ledger_ref 없음)",
                        "Judge.completed: !isPosted(ledger)");
            }
            case 1 -> {
                Charge c = charge("COMPLETED", true, false, false);
                debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
                return inj(type, "②", c, "COMPLETED·출금 성공·원장 없음(단건 404)",
                        "Service.facts 원장 단건 재조회 → Judge.completed: !isPosted(ledger)");
            }
            case 2 -> {
                Charge c = charge("FAILED", true, false, false);
                debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
                return inj(type, "③", c, "FAILED·출금 SUCCEEDED(bank_tx_id 저장 일치)",
                        "Judge.failed: isSucceeded(debit)");
            }
            default -> {
                Charge c = charge("REFUNDED", true, false, false);
                debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
                refund(c, "FAILED", AMOUNT, null);
                return inj(type, "④", c, "REFUNDED·출금 성공·환불 FAILED(refund_bank_tx_id 없음)",
                        "Judge.refunded: !isSucceeded(refund)");
            }
        }
    }

    private Injection creditWithoutBankDebit(int i) {
        String type = "CREDIT_WITHOUT_BANK_DEBIT";
        Charge c = charge("COMPLETED", false, false, true);
        ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", walletId), at());
        if (i % 2 == 0) {
            return inj(type, "①", c, "COMPLETED·원장 POSTED·출금 없음(단건 404, bank_tx_id 없음)",
                    "Service.facts 출금 단건 재조회 → Judge.completed: !isSucceeded(debit)");
        }
        debit(c, "FAILED", AMOUNT, null);
        return inj(type, "②", c, "COMPLETED·같은 충전의 출금 FAILED·원장 POSTED",
                "Judge.completed: !isSucceeded(debit)");
    }

    private Injection creditAndRefundBoth(int i) {
        String type = "CREDIT_AND_REFUND_BOTH";
        if (i % 2 == 0) {
            Charge c = charge("COMPLETED", true, true, true);
            debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
            ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", walletId), at());
            refund(c, "SUCCEEDED", AMOUNT, c.refundBankTxId);
            return inj(type, "①", c, "COMPLETED·출금·원장 정상·환불 SUCCEEDED(refund_bank_tx_id 일치)",
                    "Judge.completed: isSucceeded(refund)");
        }
        Charge c = charge("REFUNDED", true, true, true);
        debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
        refund(c, "SUCCEEDED", AMOUNT, c.refundBankTxId);
        ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", walletId), at());
        return inj(type, "②", c, "REFUNDED·출금·환불 정상·원장 POSTED(ledger_ref 일치)",
                "Judge.refunded: isPosted(ledger)");
    }

    private Injection amountMismatch(int i) {
        String type = "AMOUNT_MISMATCH";
        switch (i % 6) {
            case 0 -> {
                Charge c = completedWithDebit(AMOUNT - 1);
                ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", walletId), at());
                return inj(type, "①", c, "COMPLETED 출금 금액 ≠ 충전 금액", "Judge.debitAmount");
            }
            case 1 -> {
                Charge c = completedWithDebit(AMOUNT);
                ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT + 1, "KRW", walletId), at());
                return inj(type, "②", c, "COMPLETED 원장 CHARGE_IN 금액 ≠ 충전 금액",
                        "Judge.ledgerAmount: entry.amount ≠ charge.amount");
            }
            case 2 -> {
                Charge c = completedWithDebit(AMOUNT);
                ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "JPY", walletId), at());
                return inj(type, "③", c, "COMPLETED 원장 CHARGE_IN 통화 ≠ 충전 통화(JPY)",
                        "Judge.ledgerAmount: entry.currency ≠ charge.currency");
            }
            case 3 -> {
                Charge c = completedWithDebit(AMOUNT);
                ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", UUID.randomUUID()), at());
                return inj(type, "④", c, "COMPLETED 원장 CHARGE_IN 항목의 walletId가 다른 지갑",
                        "Judge.ledgerAmount: chargeInEntry(walletId) 없음");
            }
            case 4 -> {
                Charge c = charge("REFUNDED", true, true, false);
                debit(c, "SUCCEEDED", AMOUNT - 1, c.bankTxId);
                refund(c, "SUCCEEDED", AMOUNT, c.refundBankTxId);
                return inj(type, "⑤", c, "REFUNDED 출금 금액 ≠ 충전 금액", "Judge.refunded → debitAmount");
            }
            default -> {
                Charge c = charge("REFUNDED", true, true, false);
                debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
                refund(c, "SUCCEEDED", AMOUNT - 2, c.refundBankTxId);
                return inj(type, "⑥", c, "REFUNDED 환불 금액 ≠ 충전 금액", "Judge.refunded: 환불 금액 규칙");
            }
        }
    }

    private Injection statusMismatch(int i) {
        String type = "STATUS_MISMATCH";
        switch (i % 6) {
            case 0 -> {
                Charge c = charge("FAILED", false, false, false);
                ledger(c, UUID.randomUUID(), "REJECTED", List.of(), at());
                return inj(type, "①", c, "[상태] FAILED인데 원장 REJECTED 거래 있음",
                        "Judge.failed: ledger Found");
            }
            case 1 -> {
                Charge c = charge("REFUNDED", false, true, false);
                debit(c, "FAILED", AMOUNT, null);
                refund(c, "SUCCEEDED", AMOUNT, c.refundBankTxId);
                return inj(type, "②", c, "[상태] REFUNDED인데 출금 FAILED·환불 SUCCEEDED (실제 은행에서 생길 수 없는 원천 데이터)",
                        "Judge.refunded: !isSucceeded(debit)");
            }
            case 2 -> {
                Charge c = charge("COMPLETED", true, false, true);
                debit(c, "SUCCEEDED", AMOUNT, UUID.randomUUID());
                ledger(c, c.ledgerRef, "POSTED", postedEntries(AMOUNT, "KRW", walletId), at());
                return inj(type, "③", c, "[ID] COMPLETED bank_tx_id ≠ 출금 기록 ID",
                        "Judge.storedIds → compareId(bank_tx_id)");
            }
            case 3 -> {
                Charge c = completedWithDebit(AMOUNT);
                ledger(c, UUID.randomUUID(), "POSTED", postedEntries(AMOUNT, "KRW", walletId), at());
                return inj(type, "④", c, "[ID] COMPLETED ledger_ref ≠ 원장 transactionId",
                        "Judge.storedIds → compareId(ledger_ref)");
            }
            case 4 -> {
                Charge c = charge("REFUNDED", true, true, false);
                debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
                refund(c, "SUCCEEDED", AMOUNT, UUID.randomUUID());
                return inj(type, "⑤", c, "[ID] REFUNDED refund_bank_tx_id ≠ 환불 기록 ID",
                        "Judge.storedIds → compareId(refund_bank_tx_id)");
            }
            default -> {
                Charge c = charge("FAILED", true, false, false);
                debit(c, "FAILED", AMOUNT, null);
                return inj(type, "⑥", c, "[ID] FAILED인데 bank_tx_id 저장, 출금은 FAILED(성공 기록 없음)",
                        "Judge.storedIds → compareId(bank_tx_id, 기대 null)");
            }
        }
    }

    private Injection bankOrphan(int i) {
        String requestId = UUID.randomUUID().toString();
        String txType = i % 2 == 0 ? "DEBIT" : "REFUND";
        String status = i % 4 < 2 ? "SUCCEEDED" : "FAILED";
        sources.addBank(new BankTransactionDetailResponse("SUCCEEDED".equals(status) ? UUID.randomUUID() : null,
                requestId, txType, status, AMOUNT), at(), true);
        return new Injection("BANK_ORPHAN", "①", "BANK:" + requestId,
                "충전의 bank/refund_request_id 어디에도 없는 은행 거래(DEBIT/REFUND × SUCCEEDED/FAILED)",
                "Service.orphans: !ownedRequestIds.contains");
    }

    private Injection ledgerOrphan(int i) {
        UUID txId = UUID.randomUUID();
        boolean posted = i % 2 == 0;
        sources.addLedger(new LedgerTransactionDetailResponse(txId, posted ? "POSTED" : "REJECTED", "CHARGE",
                UUID.randomUUID(), posted ? postedEntries(AMOUNT, "KRW", walletId) : List.of()), at(), true);
        return new Injection("LEDGER_ORPHAN", "①", "LEDGER:" + txId,
                "충전 id에 없는 referenceId의 원장 거래(POSTED/REJECTED)", "Service.orphans: !chargeIds.contains");
    }

    private Injection reviewRequired(int i) {
        Charge c = charge("MANUAL_REVIEW", i % 2 == 0, false, false);
        if (i % 2 == 0) {
            debit(c, "SUCCEEDED", AMOUNT, c.bankTxId);
        }
        return inj("REVIEW_REQUIRED", "①", c, "MANUAL_REVIEW 충전(출금 성공 기록 있음/없음)",
                "Judge.judge: case MANUAL_REVIEW");
    }

    // ================= 정상 충전 =================

    /** COMPLETED 50% / FAILED 25%(출금 FAILED 기록 있음·없음 반반) / REFUNDED 25%(원장 REJECTED 있음·없음 반반) */
    private Set<String> normalCharges(int count) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < count; i++) {
            keys.add(key(normal(i, null)));
        }
        return keys;
    }

    /** 1일 기간에 created_at·기록 시각을 고르게 분포 */
    private void normalChargesSpread(int count) {
        long stepNanos = DAY.toNanos() / count;
        for (int i = 0; i < count; i++) {
            normal(i, FROM.plusNanos(stepNanos * i));
        }
    }

    private Charge normal(int i, Instant createdAt) {
        Instant at = createdAt != null ? createdAt : at();
        Instant recordAt = at.plusMillis(500);
        switch (i % 4) {
            case 0, 1 -> {
                Charge c = charge("COMPLETED", true, false, true, at);
                sources.addBank(new BankTransactionDetailResponse(c.bankTxId, c.bankRequestId, "DEBIT", "SUCCEEDED",
                        AMOUNT), recordAt, true);
                sources.addLedger(new LedgerTransactionDetailResponse(c.ledgerRef, "POSTED", "CHARGE", c.id,
                        postedEntries(AMOUNT, "KRW", walletId)), recordAt, true);
                return c;
            }
            case 2 -> {
                Charge c = charge("FAILED", false, false, false, at);
                if ((i / 4) % 2 == 0) {
                    sources.addBank(new BankTransactionDetailResponse(null, c.bankRequestId, "DEBIT", "FAILED",
                            AMOUNT), recordAt, true);
                }
                return c;
            }
            default -> {
                Charge c = charge("REFUNDED", true, true, false, at);
                sources.addBank(new BankTransactionDetailResponse(c.bankTxId, c.bankRequestId, "DEBIT", "SUCCEEDED",
                        AMOUNT), recordAt, true);
                sources.addBank(new BankTransactionDetailResponse(c.refundBankTxId, c.refundRequestId, "REFUND",
                        "SUCCEEDED", AMOUNT), recordAt.plusMillis(100), true);
                if ((i / 4) % 2 == 0) {
                    sources.addLedger(new LedgerTransactionDetailResponse(UUID.randomUUID(), "REJECTED", "CHARGE",
                            c.id, List.of()), recordAt, true);
                }
                return c;
            }
        }
    }

    // ================= 데이터 준비 =================

    private static final class Charge {
        final UUID id = UUID.randomUUID();
        final String bankRequestId = UUID.randomUUID().toString();
        final String refundRequestId = UUID.randomUUID().toString();
        UUID bankTxId;
        UUID refundBankTxId;
        UUID ledgerRef;
    }

    private Charge completedWithDebit(long debitAmount) {
        Charge c = charge("COMPLETED", true, false, true);
        debit(c, "SUCCEEDED", debitAmount, c.bankTxId);
        return c;
    }

    private Charge charge(String status, boolean bankTx, boolean refundTx, boolean ledgerRef) {
        return charge(status, bankTx, refundTx, ledgerRef, at());
    }

    private Charge charge(String status, boolean bankTx, boolean refundTx, boolean ledgerRef, Instant createdAt) {
        Charge c = new Charge();
        c.bankTxId = bankTx ? UUID.randomUUID() : null;
        c.refundBankTxId = refundTx ? UUID.randomUUID() : null;
        c.ledgerRef = ledgerRef ? UUID.randomUUID() : null;
        Timestamp ts = Timestamp.from(createdAt);
        chargeRows.add(new Object[]{c.id, UUID.randomUUID(), walletId, AMOUNT, status, UUID.randomUUID(),
                c.bankRequestId, c.refundRequestId, c.bankTxId, c.refundBankTxId, c.ledgerRef, ts, ts});
        return c;
    }

    private void flushCharges() {
        jdbcTemplate.batchUpdate("""
                INSERT INTO charge_requests (id, user_id, wallet_id, amount, currency, bank_code, account_number_enc,
                    holder_name_snapshot, status, idempotency_key, bank_request_id, refund_request_id,
                    bank_tx_id, refund_bank_tx_id, ledger_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'KRW', '004', 'enc', '홍길동', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, chargeRows);
        chargeRows.clear();
    }

    /** 기간 안 시각 (순서대로 1초 간격, 1일 안) */
    private Instant at() {
        return FROM.plusSeconds(60 + (seq++ % 80_000));
    }

    private void debit(Charge c, String status, long amount, UUID txId) {
        sources.addBank(new BankTransactionDetailResponse(txId, c.bankRequestId, "DEBIT", status, amount), at(), true);
    }

    private void refund(Charge c, String status, long amount, UUID txId) {
        sources.addBank(new BankTransactionDetailResponse(txId, c.refundRequestId, "REFUND", status, amount), at(),
                true);
    }

    private void ledger(Charge c, UUID txId, String status, List<Entry> entries, Instant at) {
        sources.addLedger(new LedgerTransactionDetailResponse(txId, status, "CHARGE", c.id, entries), at, true);
    }

    private static List<Entry> postedEntries(long amount, String currency, UUID creditWallet) {
        return List.of(
                new Entry("SYSTEM", null, "CHARGE_CLEARING", currency, "DEBIT", amount, "CHARGE_OUT"),
                new Entry("USER", creditWallet, "WALLET", currency, "CREDIT", amount, "CHARGE_IN"));
    }

    private static String key(Charge c) {
        return "C:" + c.id;
    }

    private static Injection inj(String type, String variant, Charge c, String condition, String basis) {
        return new Injection(type, variant, key(c), condition, basis);
    }

    private record Injection(String type, String variant, String key, String condition, String basis) {
    }

    private static final class TypeStat {
        int injected;
        int hit;
        int otherRows;
        int duplicates;
        final Set<String> otherTypes = new TreeSet<>();
    }

    private static final class VariantStat {
        final String type;
        final String variant;
        final String condition;
        final String basis;
        int injected;
        int hit;
        final Set<String> actual = new TreeSet<>();

        VariantStat(String type, String variant, String condition, String basis) {
            this.type = type;
            this.variant = variant;
            this.condition = condition;
            this.basis = basis;
        }
    }

    // ================= 결과 =================

    /** 결과 행을 충전(C:id) 또는 고아(BANK:requestId / LEDGER:transactionId) 키로 묶음 */
    private Map<String, List<String>> resultRowsByKey(UUID runId) {
        Map<String, List<String>> rows = new TreeMap<>();
        jdbcTemplate.query("""
                SELECT charge_id, source_type, source_key, mismatch_type FROM reconciliation_results
                 WHERE run_id = ? ORDER BY mismatch_type
                """, rs -> {
            Object chargeId = rs.getObject("charge_id");
            String k = chargeId != null ? "C:" + chargeId : rs.getString("source_type") + ":" + rs.getString("source_key");
            rows.computeIfAbsent(k, x -> new ArrayList<>()).add(rs.getString("mismatch_type"));
        }, runId);
        return rows;
    }

    private static List<String> withoutUnavailable(List<String> types) {
        return types.stream().filter(t -> !SOURCE_UNAVAILABLE.equals(t)).toList();
    }

    // ================= 은행·원장 대역 (실제 API 페이지네이션) =================

    private static final class Sources {
        private record Timed<T>(T item, Instant at, boolean listed) {
        }

        final List<Timed<BankTransactionDetailResponse>> bank = new ArrayList<>();
        final Map<String, BankTransactionDetailResponse> bankByRequest = new HashMap<>();
        final List<Timed<LedgerTransactionDetailResponse>> ledger = new ArrayList<>();
        final Map<UUID, LedgerTransactionDetailResponse> ledgerByReference = new HashMap<>();
        final Set<String> bankUnavailable = new HashSet<>();
        final Set<UUID> ledgerUnavailable = new HashSet<>();
        final Set<Integer> bankSizes = new TreeSet<>();
        final Set<Integer> ledgerSizes = new TreeSet<>();
        final AtomicInteger bankPages = new AtomicInteger();
        final AtomicInteger ledgerPages = new AtomicInteger();
        final AtomicInteger bankSingleCalls = new AtomicInteger();
        final AtomicInteger ledgerSingleCalls = new AtomicInteger();
        private boolean sorted;

        void addBank(BankTransactionDetailResponse r, Instant at, boolean listed) {
            bank.add(new Timed<>(r, at, listed));
            bankByRequest.put(r.requestId(), r);
            sorted = false;
        }

        void addLedger(LedgerTransactionDetailResponse r, Instant at, boolean listed) {
            ledger.add(new Timed<>(r, at, listed));
            ledgerByReference.put(r.referenceId(), r);
            sorted = false;
        }

        private synchronized void sort() {
            if (!sorted) {
                bank.sort(Comparator.comparing(Timed::at));
                ledger.sort(Comparator.comparing(Timed::at));
                sorted = true;
            }
        }

        private static <T> List<T> inPeriod(List<Timed<T>> all, Instant from, Instant to) {
            return all.stream().filter(t -> t.listed() && !t.at().isBefore(from) && t.at().isBefore(to))
                    .map(Timed::item).toList();
        }

        ApiResponse<PageResponse<BankTransactionDetailResponse>> bankPage(Instant from, Instant to, int page,
                                                                         int size) {
            sort();
            bankSizes.add(size);
            bankPages.incrementAndGet();
            List<BankTransactionDetailResponse> all = inPeriod(bank, from, to);
            int start = Math.min(page * size, all.size());
            int end = Math.min(start + size, all.size());
            int totalPages = (all.size() + size - 1) / size;
            return ApiResponse.success(new PageResponse<>(all.subList(start, end), page, size, all.size(),
                    totalPages));
        }

        ApiResponse<BankTransactionDetailResponse> bankSingle(String requestId) {
            bankSingleCalls.incrementAndGet();
            if (bankUnavailable.contains(requestId)) {
                throw FeignFailures.error(503, "COMMON_SERVICE_UNAVAILABLE");
            }
            BankTransactionDetailResponse r = bankByRequest.get(requestId);
            if (r == null) {
                throw FeignFailures.error(404, "BANK_TRANSACTION_NOT_FOUND");
            }
            return ApiResponse.success(r);
        }

        ApiResponse<LedgerTransactionPeriodResponse> ledgerPage(Instant from, Instant to, String cursor, int size) {
            sort();
            ledgerSizes.add(size);
            ledgerPages.incrementAndGet();
            List<LedgerTransactionDetailResponse> all = inPeriod(ledger, from, to);
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(start + size, all.size());
            String next = end < all.size() ? String.valueOf(end) : null;
            return ApiResponse.success(new LedgerTransactionPeriodResponse(all.subList(start, end), next));
        }

        ApiResponse<LedgerTransactionDetailResponse> ledgerSingle(UUID referenceId) {
            ledgerSingleCalls.incrementAndGet();
            if (ledgerUnavailable.contains(referenceId)) {
                throw FeignFailures.error(503, "COMMON_SERVICE_UNAVAILABLE");
            }
            LedgerTransactionDetailResponse r = ledgerByReference.get(referenceId);
            if (r == null) {
                throw FeignFailures.error(404, "LEDGER_TRANSACTION_NOT_FOUND");
            }
            return ApiResponse.success(r);
        }

        String pagingSummary() {
            return String.format("대역 호출: 은행 목록 %d페이지 size=%s, 원장 목록 %d페이지 size=%s, 은행 단건 %d회, 원장 단건 %d회%n",
                    bankPages.get(), bankSizes, ledgerPages.get(), ledgerSizes, bankSingleCalls.get(),
                    ledgerSingleCalls.get());
        }

        List<Executable> pagingChecks() {
            return List.of(
                    () -> assertEquals(Set.of(100), bankSizes, "은행 목록 size"),
                    () -> assertEquals(Set.of(100), ledgerSizes, "원장 목록 size"));
        }
    }
}

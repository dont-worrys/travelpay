package com.travelpay.ledger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기간 목록 조회(커서)의 새 인덱스 전후 성능 측정. 기본 test에서 빠지고 performanceTest로만 실행한다.
 * <p>
 * Flyway는 V5까지만 적용해 새 인덱스 없이 시작하고, 규모마다
 * 새 인덱스 DROP → 데이터 비우고 재생성 → ANALYZE → 전 측정 → V6 파일 실행 → ANALYZE → 후 측정.
 * 기존 인덱스(기본키, uk_ledger_transactions_reference 등)는 건드리지 않는다.
 * 전후 조건을 같게 하려고 autovacuum을 끈다.
 */
@Tag("performance")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "spring.flyway.target=5"
})
class LedgerTransactionPeriodPerformanceTest {

    private static final String SCHEMA = "ledger_service";
    private static final String PERIOD = "/internal/v1/ledger/transactions/period";
    private static final String NEW_INDEX = "ix_ledger_transactions_reference_created";
    private static final int[] SCALES = {10_000, 100_000, 1_200_000};
    private static final int USERS = 10_000;
    private static final int PAGE_SIZE = 100;
    private static final int RUNS = 5;
    private static final long TIMEOUT_MS = 3_000;
    /** 데이터 1년 구간과 조회하는 CHARGE 하루 */
    private static final String YEAR_START = "2025-01-01T00:00:00Z";
    private static final Instant DAY_FROM = Instant.parse("2025-07-01T00:00:00Z");
    private static final Instant DAY_TO = Instant.parse("2025-07-02T00:00:00Z");

    private static final String TX_COLUMNS = "select lt1_0.id,lt1_0.created_at,lt1_0.description,"
            + "lt1_0.original_transaction_id,lt1_0.reference_id,lt1_0.reference_type,lt1_0.reject_reason,"
            + "lt1_0.request_hash,lt1_0.status,lt1_0.user_id from ledger_transactions lt1_0 ";
    private static final String ENTRIES_SQL = "select le1_0.transaction_id,la1_0.owner_type,la1_0.wallet_id,"
            + "la1_0.account_type,le1_0.currency,le1_0.direction,le1_0.amount,le1_0.entry_type "
            + "from ledger_entries le1_0 join ledger_accounts la1_0 on la1_0.id=le1_0.account_id "
            + "where le1_0.transaction_id in (%s) order by le1_0.id";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA)
            .withCommand("postgres", "-c", "fsync=off", "-c", "autovacuum=off");

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

    private final StringBuilder report = new StringBuilder();

    @Test
    void 기간_목록_새_인덱스_전후_측정() throws Exception {
        line("## 환경");
        line(jdbcTemplate.queryForObject("select version()", String.class));
        for (String setting : List.of("shared_buffers", "work_mem", "jit", "max_parallel_workers_per_gather",
                "autovacuum", "plan_cache_mode")) {
            line(setting + " = " + jdbcTemplate.queryForObject("show " + setting, String.class));
        }
        line("Docker 호스트 CPU = " + Runtime.getRuntime().availableProcessors() + " (JVM 기준)");
        createAccounts();

        for (int scale : SCALES) {
            measureScale(scale);
        }
        System.out.println("\n===== PERF REPORT =====\n" + report + "===== END PERF REPORT =====");
    }

    private void measureScale(int scale) throws Exception {
        line("");
        line("## 규모 " + scale);
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + NEW_INDEX);

        long genStart = System.nanoTime();
        generate(scale);
        long genMs = (System.nanoTime() - genStart) / 1_000_000;
        analyze();
        line("데이터 생성(TRUNCATE 후 재생성) " + genMs + " ms, 거래 "
                + count("ledger_transactions") + " / 항목 " + count("ledger_entries"));
        line("유형 분포: " + jdbcTemplate.queryForList("""
                SELECT reference_type || '=' || count(*) FROM ledger_transactions
                GROUP BY reference_type ORDER BY reference_type
                """, String.class));
        line("created_at 범위: " + jdbcTemplate.queryForObject(
                "SELECT min(created_at)::text || ' ~ ' || max(created_at)::text FROM ledger_transactions",
                String.class));

        List<Row> rows = chargeDay();
        List<Position> positions = positions(rows);
        line("조회 CHARGE 하루 [" + DAY_FROM + ", " + DAY_TO + ") 건수 " + rows.size()
                + ", 페이지(" + PAGE_SIZE + "건) " + pages(rows.size()));

        measureState(scale, "전(새 인덱스 없음)", positions);

        long idxStart = System.nanoTime();
        jdbcTemplate.execute(new ClassPathResource(
                "db/migration/V6__add_ledger_transactions_reference_created_index.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        analyze();
        line("V6 인덱스 생성 + ANALYZE " + (System.nanoTime() - idxStart) / 1_000_000 + " ms, 인덱스 크기 "
                + jdbcTemplate.queryForObject("SELECT pg_size_pretty(pg_relation_size(?::regclass))",
                String.class, NEW_INDEX));

        measureState(scale, "후(새 인덱스)", positions);
    }

    private void measureState(int scale, String state, List<Position> positions) throws Exception {
        line("");
        line("### " + scale + " / " + state);
        for (Position p : positions) {
            if (p.na()) {
                line("- " + p.name() + ": 해당 없음 (페이지 수 부족)");
                continue;
            }
            Explain custom = explainMedian(txSqlLiteral(p));
            Explain entries = explainMedian(ENTRIES_SQL.formatted(p.pageIds().stream()
                    .map(id -> "'" + id + "'").collect(Collectors.joining(","))));
            String generic = genericPlan(p);
            String customShape = shape(custom.plan());
            String genericShape = generic == null ? "TIMEOUT" : shape(generic);
            Api api = apiMedian(p);
            line("- " + p.name() + " (after=" + (p.afterId() == null ? "없음" : p.afterCreatedAt()) + ")");
            line("  - 거래 조회 DB Execution Time 중앙값: " + custom.median() + " / 5회 " + custom.runs());
            line("  - 거래 조회 계획(실제 값): " + customShape);
            line("  - 거래 조회 계획(force_generic_plan): " + genericShape
                    + (customShape.equals(genericShape) ? "  => 동일" : "  => 다름"));
            line("  - 거래 조회 상세: " + detail(custom.plan()));
            line("  - entries 조회 DB Execution Time 중앙값: " + entries.median() + ", 계획: " + shape(entries.plan()));
            line("  - API 응답 중앙값: " + api.median() + " / 5회 " + api.runs() + ", 상태 " + api.statuses());
            System.out.println("---- 전체 계획: " + scale + " / " + state + " / " + p.name() + " (실제 값)\n"
                    + custom.plan() + "\n---- (force_generic_plan)\n" + generic);
        }
    }

    // =====================================================================
    // 데이터 생성
    // =====================================================================

    private String chargeSource;
    private String merchant;
    private String fxKrw;
    private String fxJpy;
    private String feeKrw;

    /** 회원 1만 명(KRW·JPY 지갑 계정) + 결제 가맹점 정산 계정 1개. 규모와 관계없이 한 번만 만든다 */
    private void createAccounts() {
        jdbcTemplate.execute("""
                CREATE TABLE perf_wallets (n INT PRIMARY KEY, user_id UUID NOT NULL, krw UUID NOT NULL,
                                           jpy UUID NOT NULL, peer_krw UUID);
                INSERT INTO perf_wallets (n, user_id, krw, jpy)
                SELECT g, gen_random_uuid(), gen_random_uuid(), gen_random_uuid() FROM generate_series(0, %d) g;
                UPDATE perf_wallets w SET peer_krw = p.krw FROM perf_wallets p WHERE p.n = (w.n + 1) %% %d;
                INSERT INTO ledger_accounts (id, owner_type, wallet_id, currency, account_type)
                SELECT krw, 'USER', user_id, 'KRW', 'WALLET' FROM perf_wallets
                UNION ALL SELECT jpy, 'USER', user_id, 'JPY', 'WALLET' FROM perf_wallets;
                INSERT INTO ledger_accounts (id, owner_type, wallet_id, currency, account_type)
                VALUES (gen_random_uuid(), 'MERCHANT', NULL, 'KRW', 'MERCHANT_SETTLEMENT');
                ANALYZE ledger_accounts;
                ANALYZE perf_wallets;
                """.formatted(USERS - 1, USERS));
        chargeSource = systemAccount("CHARGE_SOURCE", "KRW");
        fxKrw = systemAccount("FX_POSITION", "KRW");
        fxJpy = systemAccount("FX_POSITION", "JPY");
        feeKrw = systemAccount("FEE_REVENUE", "KRW");
        merchant = jdbcTemplate.queryForObject(
                "SELECT id::text FROM ledger_accounts WHERE account_type = 'MERCHANT_SETTLEMENT'", String.class);
    }

    private String systemAccount(String type, String currency) {
        return jdbcTemplate.queryForObject("""
                SELECT id::text FROM ledger_accounts
                WHERE owner_type = 'SYSTEM' AND account_type = ? AND currency = ?
                """, String.class, type, currency);
    }

    /**
     * 비우고 다시 생성한다. 유형은 행 번호 % 10으로 정확히 CHARGE 4·PAYMENT 3·TRANSFER 2·EXCHANGE 1,
     * created_at은 1년 구간 균등 난수(고정 시드). 실제 적재처럼 created_at 순서로 넣어 물리 순서를 맞춘다.
     * PAYMENT_REFUND는 분포에서 제외.
     */
    private void generate(int scale) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("TRUNCATE ledger_entries, ledger_transactions");
                st.execute("SELECT setseed(0.42)");
                st.execute("""
                        INSERT INTO ledger_transactions
                            (id, reference_type, reference_id, request_hash, user_id, status, created_at)
                        SELECT gen_random_uuid(), t.reference_type, gen_random_uuid(), repeat('0', 64), t.user_id,
                               'POSTED', t.created_at
                        FROM (
                            SELECT CASE WHEN i %% 10 < 4 THEN 'CHARGE' WHEN i %% 10 < 7 THEN 'PAYMENT'
                                        WHEN i %% 10 < 9 THEN 'TRANSFER' ELSE 'EXCHANGE' END AS reference_type,
                                   w.user_id,
                                   timestamptz '%s' + random() * interval '365 days' AS created_at
                            FROM generate_series(0, %d) i
                            JOIN perf_wallets w ON w.n = (i / 10) %% %d
                        ) t
                        ORDER BY t.created_at
                        """.formatted(YEAR_START, scale - 1, USERS));
                st.execute("""
                        INSERT INTO ledger_entries
                            (id, transaction_id, account_id, currency, entry_type, direction, amount,
                             balance_after, created_at)
                        SELECT gen_random_uuid(), t.id,
                               CASE s.acct WHEN 'USER_KRW' THEN w.krw WHEN 'USER_JPY' THEN w.jpy
                                           WHEN 'PEER_KRW' THEN w.peer_krw
                                           WHEN 'CHARGE_SOURCE' THEN '%s'::uuid WHEN 'MERCHANT' THEN '%s'::uuid
                                           WHEN 'FX_KRW' THEN '%s'::uuid WHEN 'FX_JPY' THEN '%s'::uuid
                                           WHEN 'FEE_KRW' THEN '%s'::uuid END,
                               s.currency, s.entry_type, s.direction, s.amount, 0, t.created_at
                        FROM ledger_transactions t
                        JOIN perf_wallets w ON w.user_id = t.user_id
                        JOIN (VALUES
                            ('CHARGE', 1, 'CHARGE_SOURCE', 'KRW', 'CHARGE_OUT', 'DEBIT', 5000),
                            ('CHARGE', 2, 'USER_KRW', 'KRW', 'CHARGE_IN', 'CREDIT', 5000),
                            ('PAYMENT', 1, 'USER_KRW', 'KRW', 'PAYMENT_OUT', 'DEBIT', 3000),
                            ('PAYMENT', 2, 'MERCHANT', 'KRW', 'PAYMENT_IN', 'CREDIT', 3000),
                            ('TRANSFER', 1, 'USER_KRW', 'KRW', 'TRANSFER_OUT', 'DEBIT', 2000),
                            ('TRANSFER', 2, 'PEER_KRW', 'KRW', 'TRANSFER_IN', 'CREDIT', 2000),
                            ('EXCHANGE', 1, 'USER_KRW', 'KRW', 'EXCHANGE_OUT', 'DEBIT', 10000),
                            ('EXCHANGE', 2, 'FX_KRW', 'KRW', 'EXCHANGE_OUT', 'CREDIT', 10000),
                            ('EXCHANGE', 3, 'FX_JPY', 'JPY', 'EXCHANGE_IN', 'DEBIT', 1000),
                            ('EXCHANGE', 4, 'USER_JPY', 'JPY', 'EXCHANGE_IN', 'CREDIT', 1000),
                            ('EXCHANGE', 5, 'USER_KRW', 'KRW', 'EXCHANGE_FEE', 'DEBIT', 100),
                            ('EXCHANGE', 6, 'FEE_KRW', 'KRW', 'EXCHANGE_FEE', 'CREDIT', 100)
                        ) s (reference_type, seq, acct, currency, entry_type, direction, amount)
                            ON s.reference_type = t.reference_type
                        ORDER BY t.created_at, t.id, s.seq
                        """.formatted(chargeSource, merchant, fxKrw, fxJpy, feeKrw));
            }
            return null;
        });
    }

    private void analyze() {
        jdbcTemplate.execute("ANALYZE ledger_transactions");
        jdbcTemplate.execute("ANALYZE ledger_entries");
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    // =====================================================================
    // 측정 위치
    // =====================================================================

    private record Row(OffsetDateTime createdAt, UUID id) {
    }

    /** afterId == null이면 첫 페이지. na면 페이지 수가 부족해 측정하지 않는다 */
    private record Position(String name, int page, OffsetDateTime afterCreatedAt, UUID afterId,
                            List<UUID> pageIds, boolean na) {
    }

    private List<Row> chargeDay() {
        return jdbcTemplate.query("""
                SELECT created_at, id FROM ledger_transactions
                WHERE reference_type = 'CHARGE' AND created_at >= ? AND created_at < ?
                ORDER BY created_at, id
                """, (rs, i) -> new Row(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, UUID.class)),
                OffsetDateTime.parse(DAY_FROM.toString()), OffsetDateTime.parse(DAY_TO.toString()));
    }

    private static int pages(int rows) {
        return Math.max(1, (rows + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 첫 페이지, 중간 페이지(pages/2, 3페이지 이상일 때), 뒤쪽(마지막) 페이지(2페이지 이상일 때) */
    private static List<Position> positions(List<Row> rows) {
        int pages = pages(rows.size());
        List<Position> positions = new ArrayList<>();
        positions.add(position("첫 페이지(1)", 0, rows));
        positions.add(pages >= 3 ? position("중간 페이지(" + (pages / 2 + 1) + ")", pages / 2, rows)
                : new Position("중간 페이지", -1, null, null, List.of(), true));
        positions.add(pages >= 2 ? position("뒤쪽 페이지(" + pages + ")", pages - 1, rows)
                : new Position("뒤쪽 페이지", -1, null, null, List.of(), true));
        return positions;
    }

    private static Position position(String name, int page, List<Row> rows) {
        List<UUID> ids = rows.subList(page * PAGE_SIZE, Math.min(rows.size(), (page + 1) * PAGE_SIZE))
                .stream().map(Row::id).toList();
        if (page == 0) {
            return new Position(name, 0, null, null, ids, false);
        }
        Row after = rows.get(page * PAGE_SIZE - 1);
        return new Position(name, page, after.createdAt(), after.id(), ids, false);
    }

    // =====================================================================
    // DB 측정
    // =====================================================================

    private record Explain(String median, List<String> runs, String plan) {
    }

    /** Hibernate 로그의 거래 조회 SQL에 실제 값을 넣은 형태 (fetch first = size + 1) */
    private static String txSqlLiteral(Position p) {
        String where = "where lt1_0.reference_type='CHARGE' and lt1_0.created_at>='" + DAY_FROM
                + "'::timestamptz and lt1_0.created_at<'" + DAY_TO + "'::timestamptz ";
        if (p.afterId() != null) {
            where += "and (lt1_0.created_at,lt1_0.id)>('" + p.afterCreatedAt() + "'::timestamptz,'"
                    + p.afterId() + "'::uuid) ";
        }
        return TX_COLUMNS + where + "order by lt1_0.created_at,lt1_0.id fetch first " + (PAGE_SIZE + 1)
                + " rows only";
    }

    /** 첫 실행을 버리고 5회 Execution Time 중앙값. 3초 넘으면 TIMEOUT */
    private Explain explainMedian(String sql) {
        return jdbcTemplate.execute((ConnectionCallback<Explain>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("SET statement_timeout = " + TIMEOUT_MS);
                List<Double> times = new ArrayList<>();
                List<String> runs = new ArrayList<>();
                String plan = null;
                for (int i = 0; i <= RUNS; i++) {
                    try {
                        plan = plan(st, "EXPLAIN (ANALYZE, BUFFERS) " + sql);
                    } catch (SQLException e) {
                        if (!"57014".equals(e.getSQLState())) {
                            throw e;
                        }
                        plan = "TIMEOUT";
                    }
                    if (i == 0) {
                        continue;
                    }
                    double ms = "TIMEOUT".equals(plan) ? Double.POSITIVE_INFINITY : executionTime(plan);
                    times.add(ms);
                    runs.add(Double.isInfinite(ms) ? "TIMEOUT" : String.format("%.3f", ms));
                }
                st.execute("RESET statement_timeout");
                return new Explain(medianText(times), runs, plan);
            }
        });
    }

    /** PREPARE + force_generic_plan으로 값과 관계없는 일반 계획 (ANALYZE 포함, 두 번째 실행 계획) */
    private String genericPlan(Position p) {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("SET statement_timeout = " + TIMEOUT_MS);
                st.execute("DEALLOCATE ALL");
                st.execute("SET plan_cache_mode = force_generic_plan");
                String execute;
                if (p.afterId() == null) {
                    st.execute("PREPARE perf_tx(varchar, timestamptz, timestamptz, int) AS " + TX_COLUMNS
                            + "where lt1_0.reference_type=$1 and lt1_0.created_at>=$2 and lt1_0.created_at<$3 "
                            + "order by lt1_0.created_at,lt1_0.id fetch first $4 rows only");
                    execute = "EXECUTE perf_tx('CHARGE', '" + DAY_FROM + "', '" + DAY_TO + "', "
                            + (PAGE_SIZE + 1) + ")";
                } else {
                    st.execute("PREPARE perf_tx(varchar, timestamptz, timestamptz, timestamptz, uuid, int) AS "
                            + TX_COLUMNS
                            + "where lt1_0.reference_type=$1 and lt1_0.created_at>=$2 and lt1_0.created_at<$3 "
                            + "and (lt1_0.created_at,lt1_0.id)>($4,$5) "
                            + "order by lt1_0.created_at,lt1_0.id fetch first $6 rows only");
                    execute = "EXECUTE perf_tx('CHARGE', '" + DAY_FROM + "', '" + DAY_TO + "', '"
                            + p.afterCreatedAt() + "', '" + p.afterId() + "', " + (PAGE_SIZE + 1) + ")";
                }
                String plan;
                try {
                    plan(st, "EXPLAIN (ANALYZE, BUFFERS) " + execute);
                    plan = plan(st, "EXPLAIN (ANALYZE, BUFFERS) " + execute);
                } catch (SQLException e) {
                    if (!"57014".equals(e.getSQLState())) {
                        throw e;
                    }
                    plan = null;
                }
                st.execute("DEALLOCATE perf_tx");
                st.execute("RESET plan_cache_mode");
                st.execute("RESET statement_timeout");
                return plan;
            }
        });
    }

    private static String plan(Statement st, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private static double executionTime(String plan) {
        return plan.lines().filter(l -> l.trim().startsWith("Execution Time:"))
                .map(l -> l.replaceAll("[^0-9.]", ""))
                .mapToDouble(Double::parseDouble).findFirst().orElseThrow();
    }

    /** 노드 이름(사용 인덱스 포함)만 남긴 계획 모양 */
    private static String shape(String plan) {
        if (plan == null || "TIMEOUT".equals(plan)) {
            return "TIMEOUT";
        }
        return plan.lines()
                .filter(l -> l.contains("(cost=") || l.contains("(never executed)"))
                .map(l -> l.replaceFirst("\\s*\\(cost=.*", "").replace("->", "").trim())
                .collect(Collectors.joining(" > "));
    }

    /** 조건 처리 방식 요약: Index Cond / Filter / 제거 행 / Sort / Buffers / JIT */
    private static String detail(String plan) {
        if (plan == null || "TIMEOUT".equals(plan)) {
            return "TIMEOUT";
        }
        return plan.lines().map(String::trim)
                .filter(l -> l.startsWith("Index Cond") || l.startsWith("Filter") || l.startsWith("Rows Removed")
                        || l.startsWith("Sort Method") || l.startsWith("Workers Launched")
                        || l.startsWith("JIT") || l.startsWith("Planning Time"))
                .collect(Collectors.joining(" | "))
                + " | " + plan.lines().map(String::trim).filter(l -> l.startsWith("Buffers")).findFirst().orElse("");
    }

    // =====================================================================
    // API 측정
    // =====================================================================

    private record Api(String median, List<String> runs, List<Integer> statuses) {
    }

    private Api apiMedian(Position p) throws Exception {
        String cursor = cursorFor(p);
        String path = PERIOD + "?referenceType=CHARGE&from=" + enc(DAY_FROM.toString()) + "&to="
                + enc(DAY_TO.toString()) + "&size=" + PAGE_SIZE + (cursor == null ? "" : "&cursor=" + enc(cursor));
        List<Double> times = new ArrayList<>();
        List<String> runs = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i <= RUNS; i++) {
            long start = System.nanoTime();
            HttpResponse<String> response = get(path);
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            if (i == 0) {
                continue;
            }
            statuses.add(response.statusCode());
            boolean timeout = response.statusCode() != 200 || ms >= TIMEOUT_MS;
            if (response.statusCode() == 200) {
                JsonNode items = jsonMapper.readTree(response.body()).get("data").get("items");
                assertEquals(p.pageIds().size(), items.size());
                assertEquals(p.pageIds().getFirst().toString(), items.get(0).get("transactionId").asString());
            }
            times.add(timeout ? Double.POSITIVE_INFINITY : ms);
            runs.add(timeout ? "TIMEOUT(" + response.statusCode() + ", " + String.format("%.1f", ms) + "ms)"
                    : String.format("%.1f", ms));
        }
        return new Api(medianText(times), runs, statuses);
    }

    /** 실제 API로 첫 페이지부터 이어 가서 대상 페이지 커서를 얻고, 커서 위치가 DB 행과 같은지 확인한다 */
    private String cursorFor(Position p) throws Exception {
        String cursor = null;
        for (int page = 0; page < p.page(); page++) {
            String path = PERIOD + "?referenceType=CHARGE&from=" + enc(DAY_FROM.toString()) + "&to="
                    + enc(DAY_TO.toString()) + "&size=" + PAGE_SIZE + (cursor == null ? "" : "&cursor=" + enc(cursor));
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), response.body());
            cursor = jsonMapper.readTree(response.body()).get("data").get("nextCursor").asString();
        }
        if (cursor != null) {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            assertTrue(decoded.endsWith(p.afterId().toString()), decoded + " vs " + Arrays.toString(parts));
        }
        return cursor;
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // =====================================================================

    private static String medianText(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        double median = sorted.get(sorted.size() / 2);
        return Double.isInfinite(median) ? "TIMEOUT" : String.format("%.3f ms", median);
    }

    private void line(String text) {
        report.append(text).append('\n');
    }
}

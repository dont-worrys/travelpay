package com.travelpay.ledger;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기간 목록 조회(커서)를 실제 Postgres와 실제 HTTP로 확인한다.
 * 거래는 JDBC로 직접 넣어 created_at을 마이크로초 단위로 정한다.
 * 테스트마다 서로 다른 날(2001년 이후)을 조회 구간으로 써서 다른 테스트 데이터와 섞이지 않는다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.SQL=DEBUG"
})
class LedgerTransactionPeriodIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final String PERIOD = "/internal/v1/ledger/transactions/period";
    /** 커서가 끝나지 않는 구현에서도 테스트가 멈추지 않도록 페이지 수 상한 */
    private static final int MAX_PAGES = 20;
    private static final AtomicInteger DAY = new AtomicInteger();

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

    @Autowired
    private DataSource dataSource;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private UUID chargeSourceAccountId;
    /** 이 테스트만 쓰는 하루 구간 [dayStart, dayEnd) */
    private Instant dayStart;
    private Instant dayEnd;

    @BeforeEach
    void setUp() {
        chargeSourceAccountId = jdbcTemplate.queryForObject("""
                SELECT id FROM ledger_accounts
                WHERE owner_type = 'SYSTEM' AND account_type = 'CHARGE_SOURCE' AND currency = 'KRW'
                """, UUID.class);
        dayStart = Instant.parse("2001-01-01T00:00:00Z").plus(DAY.getAndIncrement(), ChronoUnit.DAYS);
        dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
    }

    // =====================================================================
    // 위험 재현 (허술한 구현에서 실패, 설계한 방식에서 통과)
    // =====================================================================

    /** 위험 1: 같은 created_at 7건이 페이지 경계(size 3)에 걸린다 */
    @Test
    void 위험_같은_시각_거래가_페이지_경계에_걸려도_중복_누락_없이_끝까지_이어진다() throws Exception {
        Instant same = dayStart.plusSeconds(3600).plus(123_456, ChronoUnit.MICROS);
        insertPosted("CHARGE", same.minus(1, ChronoUnit.MICROS), 1);
        for (int i = 0; i < 7; i++) {
            insertPosted("CHARGE", same, 10 + i);
        }
        insertPosted("CHARGE", same.plus(1, ChronoUnit.MICROS), 99);

        PageWalk walk = walk("CHARGE", dayStart, dayEnd, 3);

        assertSameAsDb(dbIds("CHARGE", dayStart, dayEnd), walk);
    }

    /** 위험 2: 같은 밀리초 안의 서로 다른 마이크로초 5건이 페이지 경계(size 3)에 걸린다 */
    @Test
    void 위험_같은_밀리초의_다른_마이크로초_거래가_페이지_경계에_걸려도_중복_누락_없다() throws Exception {
        Instant millis = dayStart.plusSeconds(7200).plusMillis(123);
        for (int micro = 1; micro <= 5; micro++) {
            insertPosted("CHARGE", millis.plus(micro, ChronoUnit.MICROS), micro);
        }
        insertPosted("CHARGE", millis.plusMillis(1), 50);

        PageWalk walk = walk("CHARGE", dayStart, dayEnd, 3);

        assertSameAsDb(dbIds("CHARGE", dayStart, dayEnd), walk);
    }

    /** 위험 3: 1페이지 커서를 다른 조건(유형·from·to)의 요청에 붙이면 400 */
    @Test
    void 위험_커서와_다른_조회_조건으로_다음_페이지를_요청하면_400이다() throws Exception {
        for (int i = 0; i < 4; i++) {
            insertPosted("CHARGE", dayStart.plusSeconds(60 + i), i + 1);
        }
        JsonNode first = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, 2);
        String cursor = first.get("nextCursor").asString();
        assertNotNull(cursor);

        List<HttpResponse<String>> mismatched = List.of(
                period("PAYMENT", dayStart.toString(), dayEnd.toString(), cursor, 2),
                period("CHARGE", dayStart.plusSeconds(1).toString(), dayEnd.toString(), cursor, 2),
                period("CHARGE", dayStart.toString(), dayEnd.plusSeconds(1).toString(), cursor, 2));

        List<Integer> statuses = mismatched.stream().map(HttpResponse::statusCode).toList();
        assertEquals(List.of(400, 400, 400), statuses, "조건이 다른 요청 응답: " + mismatched.stream()
                .map(r -> r.statusCode() + " " + r.body()).toList());
        for (HttpResponse<String> response : mismatched) {
            assertEquals("LEDGER_CURSOR_MISMATCH", errorCode(response));
        }
    }

    /** 위험 4: 100건 페이지(거래마다 항목 2건)도 쿼리는 거래 1회 + 항목 일괄 1회 */
    @Test
    void 위험_한_페이지_100건_조회는_쿼리_2회다() throws Exception {
        for (int i = 0; i < 101; i++) {
            insertPosted("CHARGE", dayStart.plusSeconds(i), i + 1);
        }
        Statistics statistics = statistics();
        statistics.clear();

        JsonNode data = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, 100);

        long statements = statistics.getPrepareStatementCount();
        assertEquals(100, data.get("items").size());
        assertEquals(2, statements, "100건 페이지 조회 쿼리 수 = " + statements);
        for (JsonNode item : data.get("items")) {
            assertEquals(2, item.get("entries").size());
        }
    }

    /**
     * 위험 5 (재현만, 해결은 대사 작업): 커밋이 늦은 거래는 커서가 그 created_at을 지나간 뒤 커밋되면
     * 이어지는 페이지에서 누락된다. 처음부터 다시 조회하면 보인다.
     */
    @Test
    void 재현_커서가_지나간_뒤_커밋된_거래는_이어지는_페이지에서_누락된다() throws Exception {
        Instant base = dayStart.plusSeconds(3600);
        UUID a = insertPosted("CHARGE", base, 1);
        UUID b = insertPosted("CHARGE", base.plusSeconds(2), 2);
        UUID c = insertPosted("CHARGE", base.plusSeconds(3), 3);
        UUID late = UUID.randomUUID();
        Instant lateAt = base.plusSeconds(1);

        JsonNode second;
        try (Connection other = dataSource.getConnection()) {
            // 1) 별도 연결에서 a와 b 사이 시각의 거래를 INSERT하고 커밋하지 않는다
            other.setAutoCommit(false);
            insertOnConnection(other, late, lateAt, 4);

            // 2) 첫 페이지(size 2): 미커밋 거래는 안 보이고, 커서가 b 위치로 lateAt을 지나간다
            JsonNode first = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, 2);
            assertEquals(List.of(a.toString(), b.toString()), ids(first));

            // 3) 커밋
            other.commit();

            // 4) 이어지는 페이지: 커서 이후(c)만 나오고 늦게 커밋된 거래는 빠진다
            second = periodData("CHARGE", dayStart.toString(), dayEnd.toString(),
                    first.get("nextCursor").asString(), 2);
        }
        assertEquals(List.of(c.toString()), ids(second), "커서 순회에서 늦게 커밋된 거래 누락");

        // 5) 처음부터 다시 조회하면 포함된다 (DB에는 정상 저장)
        PageWalk again = walk("CHARGE", dayStart, dayEnd, 2);
        assertEquals(List.of(a.toString(), late.toString(), b.toString(), c.toString()), again.ids());
    }

    private void insertOnConnection(Connection connection, UUID id, Instant createdAt, long tag) throws Exception {
        try (PreparedStatement tx = connection.prepareStatement("""
                INSERT INTO ledger_transactions
                    (id, reference_type, reference_id, request_hash, status, created_at)
                VALUES (?, 'CHARGE', ?, ?, 'POSTED', ?)
                """)) {
            tx.setObject(1, id);
            tx.setObject(2, UUID.randomUUID());
            tx.setString(3, "0".repeat(64));
            tx.setObject(4, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            tx.executeUpdate();
        }
        for (String direction : List.of("DEBIT", "CREDIT")) {
            try (PreparedStatement entry = connection.prepareStatement("""
                    INSERT INTO ledger_entries
                        (id, transaction_id, account_id, currency, entry_type, direction, amount, balance_after,
                         created_at)
                    VALUES (?, ?, ?, 'KRW', 'CHARGE_IN', ?, ?, 0, now())
                    """)) {
                entry.setObject(1, UUID.randomUUID());
                entry.setObject(2, id);
                entry.setObject(3, chargeSourceAccountId);
                entry.setString(4, direction);
                entry.setLong(5, tag);
                entry.executeUpdate();
            }
        }
    }

    // =====================================================================
    // 일반
    // =====================================================================

    @Test
    void 거래가_없는_페이지는_쿼리_1회이고_nextCursor는_null이다() throws Exception {
        Statistics statistics = statistics();
        statistics.clear();

        JsonNode data = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, null);

        assertEquals(0, data.get("items").size());
        assertTrue(data.get("nextCursor").isNull());
        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void 기간은_from_포함_to_제외다() throws Exception {
        Instant from = dayStart.plusSeconds(100);
        Instant to = dayStart.plusSeconds(200);
        insertPosted("CHARGE", from.minus(1, ChronoUnit.MICROS), 1);
        UUID atFrom = insertPosted("CHARGE", from, 2);
        UUID beforeTo = insertPosted("CHARGE", to.minus(1, ChronoUnit.MICROS), 3);
        insertPosted("CHARGE", to, 4);

        JsonNode data = periodData("CHARGE", from.toString(), to.toString(), null, null);

        assertEquals(List.of(atFrom.toString(), beforeTo.toString()), ids(data));
        assertTrue(data.get("nextCursor").isNull());
    }

    @Test
    void from과_to의_마이크로초_미만은_다음_마이크로초로_올린다() throws Exception {
        Instant t = dayStart.plusSeconds(300).plus(500, ChronoUnit.MICROS);
        insertPosted("CHARGE", t, 1);
        UUID next = insertPosted("CHARGE", t.plus(1, ChronoUnit.MICROS), 2);

        // from = t + 1ns → t + 1µs 로 올림: t 제외. to = t + 1µs + 1ns → t + 2µs: t + 1µs 포함
        JsonNode data = periodData("CHARGE", t.plusNanos(1).toString(),
                t.plus(1, ChronoUnit.MICROS).plusNanos(1).toString(), null, null);

        assertEquals(List.of(next.toString()), ids(data));
    }

    @Test
    void REJECTED_거래도_순서대로_포함되고_entries는_빈_배열이다() throws Exception {
        UUID posted1 = insertPosted("CHARGE", dayStart.plusSeconds(10), 7);
        UUID rejected = insertRejected("CHARGE", dayStart.plusSeconds(11));
        UUID posted2 = insertPosted("CHARGE", dayStart.plusSeconds(12), 8);
        insertPosted("PAYMENT", dayStart.plusSeconds(11), 9);

        JsonNode data = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, null);

        assertEquals(List.of(posted1.toString(), rejected.toString(), posted2.toString()), ids(data));
        JsonNode rejectedItem = data.get("items").get(1);
        assertEquals("REJECTED", rejectedItem.get("status").asString());
        assertEquals("INSUFFICIENT_BALANCE", rejectedItem.get("rejectReason").asString());
        assertTrue(rejectedItem.get("entries").isArray());
        assertEquals(0, rejectedItem.get("entries").size());
        assertEntriesOfTag(data.get("items").get(0), 7);
        assertEntriesOfTag(data.get("items").get(2), 8);
    }

    @Test
    void 항목은_거래별로_묶이고_단건_조회와_같은_필드로_나온다() throws Exception {
        UUID a = insertPosted("CHARGE", dayStart.plusSeconds(1), 11);
        UUID b = insertPosted("CHARGE", dayStart.plusSeconds(2), 22);

        JsonNode data = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, null);

        assertEquals(List.of(a.toString(), b.toString()), ids(data));
        JsonNode item = data.get("items").get(0);
        for (String field : List.of("transactionId", "status", "rejectReason", "referenceType", "referenceId",
                "createdAt", "entries")) {
            assertTrue(item.has(field), field);
        }
        assertEquals(7, item.size(), item.toString());
        assertEquals(createdAtInDb(a), Instant.parse(item.get("createdAt").asString()));
        assertEntriesOfTag(item, 11);
        assertEntriesOfTag(data.get("items").get(1), 22);
        assertFalse(data.toString().contains("balanceAfter"));
    }

    @Test
    void size_기본은_100이고_101_이상은_100으로_줄인다() throws Exception {
        for (int i = 0; i < 101; i++) {
            insertPosted("CHARGE", dayStart.plusSeconds(i), i + 1);
        }

        JsonNode byDefault = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, null);
        JsonNode over = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, 101);
        JsonNode rest = periodData("CHARGE", dayStart.toString(), dayEnd.toString(),
                over.get("nextCursor").asString(), 101);

        assertEquals(100, byDefault.get("items").size());
        assertFalse(byDefault.get("nextCursor").isNull());
        assertEquals(100, over.get("items").size());
        assertEquals(1, rest.get("items").size());
        assertTrue(rest.get("nextCursor").isNull());
    }

    @Test
    void 남은_행이_정확히_size건이면_nextCursor는_null이다() throws Exception {
        for (int i = 0; i < 3; i++) {
            insertPosted("CHARGE", dayStart.plusSeconds(i), i + 1);
        }

        JsonNode data = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, 3);

        assertEquals(3, data.get("items").size());
        assertTrue(data.get("nextCursor").isNull());
    }

    @Test
    void 다음_커서는_돌려준_마지막_행_위치라_다음_페이지가_그_다음_행부터다() throws Exception {
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inserted.add(insertPosted("CHARGE", dayStart.plusSeconds(i), i + 1));
        }

        JsonNode first = periodData("CHARGE", dayStart.toString(), dayEnd.toString(), null, 2);
        JsonNode second = periodData("CHARGE", dayStart.toString(), dayEnd.toString(),
                first.get("nextCursor").asString(), 2);

        assertEquals(List.of(inserted.get(0).toString(), inserted.get(1).toString()), ids(first));
        assertEquals(List.of(inserted.get(2).toString(), inserted.get(3).toString()), ids(second));
    }

    @Test
    void 같은_시각을_다른_표기로_보내도_커서가_통한다() throws Exception {
        for (int i = 0; i < 3; i++) {
            insertPosted("CHARGE", dayStart.plusSeconds(i), i + 1);
        }
        String fromKst = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dayStart.atOffset(ZoneOffset.ofHours(9)));
        String toKst = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dayEnd.atOffset(ZoneOffset.ofHours(9)));

        JsonNode first = periodData("CHARGE", fromKst, toKst, null, 2);
        JsonNode second = periodData("CHARGE", dayStart.toString(), dayEnd.toString(),
                first.get("nextCursor").asString(), 2);

        assertEquals(2, first.get("items").size());
        assertEquals(1, second.get("items").size());
    }

    @Test
    void 요청_형식_오류는_400이다() throws Exception {
        String from = dayStart.toString();
        String to = dayEnd.toString();
        Map<String, HttpResponse<String>> responses = new LinkedHashMap<>();
        responses.put("from 형식", period("CHARGE", "2001-01-01", to, null, null));
        responses.put("to 형식", period("CHARGE", from, "not-a-time", null, null));
        responses.put("referenceType 값", period("UNKNOWN", from, to, null, null));
        responses.put("referenceType 누락", get(PERIOD + "?from=" + enc(from) + "&to=" + enc(to)));
        responses.put("from 누락", get(PERIOD + "?referenceType=CHARGE&to=" + enc(to)));
        responses.put("to 누락", get(PERIOD + "?referenceType=CHARGE&from=" + enc(from)));
        responses.put("size 숫자 아님", get(PERIOD + "?referenceType=CHARGE&from=" + enc(from) + "&to=" + enc(to)
                + "&size=abc"));

        for (Map.Entry<String, HttpResponse<String>> e : responses.entrySet()) {
            assertEquals(400, e.getValue().statusCode(), e.getKey() + ": " + e.getValue().body());
        }
    }

    @Test
    void from이_to보다_늦으면_400이고_같으면_빈_목록이다() throws Exception {
        HttpResponse<String> reversed = period("CHARGE", dayEnd.toString(), dayStart.toString(), null, null);
        JsonNode empty = periodData("CHARGE", dayStart.toString(), dayStart.toString(), null, null);

        assertEquals(400, reversed.statusCode(), reversed.body());
        assertEquals("LEDGER_INVALID_PERIOD", errorCode(reversed));
        assertEquals(0, empty.get("items").size());
    }

    @Test
    void size가_1보다_작으면_400이다() throws Exception {
        for (Integer size : List.of(0, -1)) {
            HttpResponse<String> response = period("CHARGE", dayStart.toString(), dayEnd.toString(), null, size);
            assertEquals(400, response.statusCode(), "size=" + size + " " + response.body());
            assertEquals("LEDGER_INVALID_PAGE_SIZE", errorCode(response));
        }
    }

    /** to = 9999-12-31: 첫 페이지가 size보다 많으면 nextCursor가 만들어지고 다음 페이지까지 이어진다 */
    @Test
    void 기간_끝이_9999년이어도_다음_커서로_끝까지_이어진다() throws Exception {
        Instant from = Instant.parse("9999-12-30T00:00:00Z");
        Instant to = Instant.parse("9999-12-31T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            insertPosted("CHARGE", from.plusSeconds(60 + i).plus(7, ChronoUnit.MICROS), i + 1);
        }

        JsonNode first = periodData("CHARGE", from.toString(), to.toString(), null, 2);
        assertEquals(2, first.get("items").size());
        assertFalse(first.get("nextCursor").isNull(), "9999년 기간 첫 페이지 nextCursor");

        PageWalk walk = walk("CHARGE", from, to, 2);
        assertEquals(List.of(2, 1), walk.pageSizes());
        assertSameAsDb(dbIds("CHARGE", from, to), walk);
    }

    @Test
    void 업무_범위_경계값은_허용한다() throws Exception {
        JsonNode data = periodData("CHARGE", "0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z", null, 1);
        assertNotNull(data.get("items"));
    }

    /** 허용 범위 [0001-01-01T00:00:00Z, 9999-12-31T23:59:59.999999Z] 밖의 from·to는 400 */
    @Test
    void 업무_범위_밖의_시각은_400이다() throws Exception {
        List<String[]> periods = List.of(
                new String[] {dayStart.toString(), "+10000-01-01T00:00:00Z"},     // 범위 밖 미래 (to)
                new String[] {"-9999-01-01T00:00:00Z", dayEnd.toString()},        // 기원전 1만 년 (from)
                new String[] {dayStart.toString(), "9999-12-31T23:59:59.9999991Z"}, // 상한 + 1ns
                new String[] {"0000-12-31T23:59:59.999999Z", dayEnd.toString()},  // 하한 - 1µs
                new String[] {"+300000-01-01T00:00:00Z", "+300001-01-01T00:00:00Z"}); // 마이크로초 long 범위 밖

        for (String[] period : periods) {
            HttpResponse<String> response = period("CHARGE", period[0], period[1], null, null);
            assertEquals(400, response.statusCode(), String.join(" ~ ", period) + " " + response.body());
            assertEquals("LEDGER_INVALID_PERIOD", errorCode(response), String.join(" ~ ", period));
        }
    }

    /** V6는 CONCURRENTLY로 트랜잭션 없이 적용되고, 만들어진 인덱스는 VALID여야 한다 */
    @Test
    void V6_인덱스는_VALID로_적용된다() {
        Boolean valid = jdbcTemplate.queryForObject("""
                SELECT i.indisvalid AND i.indisready
                FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = 'ix_ledger_transactions_reference_created'
                """, Boolean.class, SCHEMA);
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '6'", Boolean.class);

        assertEquals(Boolean.TRUE, valid);
        assertEquals(Boolean.TRUE, applied);
    }

    @Test
    void 잘못된_커서는_400이다() throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        List<String> cursors = List.of(
                "not base64!!",
                b64.encodeToString("garbage".getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString("v1:CHARGE:1:2:3".getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString(("v1:CHARGE:x:2:3:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString("v1:CHARGE:1:2:3:not-uuid".getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString(("v9:CHARGE:1:2:3:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString(("v1:NOPE:1:2:3:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));

        for (String cursor : cursors) {
            HttpResponse<String> response = period("CHARGE", dayStart.toString(), dayEnd.toString(), cursor, null);
            assertEquals(400, response.statusCode(), cursor + " " + response.body());
            assertEquals("LEDGER_INVALID_CURSOR", errorCode(response), cursor);
        }
    }

    // ---- 커서 순회 ----

    private record PageWalk(List<String> ids, List<Integer> pageSizes, boolean stoppedByLimit) {
    }

    private PageWalk walk(String referenceType, Instant from, Instant to, int size) throws Exception {
        List<String> ids = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode data = periodData(referenceType, from.toString(), to.toString(), cursor, size);
            List<String> page = ids(data);
            ids.addAll(page);
            pageSizes.add(page.size());
            cursor = data.get("nextCursor").isNull() ? null : data.get("nextCursor").asString();
            pages++;
        } while (cursor != null && pages < MAX_PAGES);
        return new PageWalk(ids, pageSizes, cursor != null);
    }

    /** 커서로 받은 전체 목록이 DB의 (created_at, id) 순 목록과 같아야 한다. 다르면 중복·누락 건수를 남긴다 */
    private static void assertSameAsDb(List<String> expected, PageWalk walk) {
        int duplicates = walk.ids().size() - new HashSet<>(walk.ids()).size();
        long missing = expected.stream().filter(id -> !walk.ids().contains(id)).count();
        String summary = "DB " + expected.size() + "건 / 받은 " + walk.ids().size() + "건, 중복 " + duplicates
                + "건, 누락 " + missing + "건, 페이지별 건수 " + walk.pageSizes()
                + (walk.stoppedByLimit() ? " (페이지 상한 " + MAX_PAGES + "에서 중단)" : "");
        assertEquals(expected, walk.ids(), summary);
    }

    // ---- 데이터 ----

    /** POSTED 거래 + 항목 2건(DEBIT/CREDIT, 금액 = tag). 금액으로 어느 거래의 항목인지 구분한다 */
    private UUID insertPosted(String referenceType, Instant createdAt, long tag) {
        UUID id = insertTransaction(referenceType, createdAt, "POSTED", null);
        for (String direction : List.of("DEBIT", "CREDIT")) {
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries
                        (id, transaction_id, account_id, currency, entry_type, direction, amount, balance_after,
                         created_at)
                    VALUES (?, ?, ?, 'KRW', 'CHARGE_IN', ?, ?, 0, now())
                    """, UUID.randomUUID(), id, chargeSourceAccountId, direction, tag);
        }
        return id;
    }

    private UUID insertRejected(String referenceType, Instant createdAt) {
        return insertTransaction(referenceType, createdAt, "REJECTED", "INSUFFICIENT_BALANCE");
    }

    private UUID insertTransaction(String referenceType, Instant createdAt, String status, String rejectReason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ledger_transactions
                    (id, reference_type, reference_id, request_hash, status, reject_reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, referenceType, UUID.randomUUID(), "0".repeat(64), status, rejectReason,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }

    private List<String> dbIds(String referenceType, Instant from, Instant to) {
        return jdbcTemplate.queryForList("""
                SELECT id::text FROM ledger_transactions
                WHERE reference_type = ? AND created_at >= ? AND created_at < ?
                ORDER BY created_at, id
                """, String.class, referenceType, OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    private Instant createdAtInDb(UUID id) {
        return jdbcTemplate.queryForObject("SELECT created_at FROM ledger_transactions WHERE id = ?",
                OffsetDateTime.class, id).toInstant();
    }

    private static void assertEntriesOfTag(JsonNode item, long tag) {
        JsonNode entries = item.get("entries");
        assertEquals(2, entries.size(), item.toString());
        for (JsonNode entry : entries) {
            assertEquals(tag, entry.get("amount").asLong(), item.toString());
            assertEquals("SYSTEM", entry.get("ownerType").asString());
            assertEquals("CHARGE_SOURCE", entry.get("accountType").asString());
            assertEquals("CHARGE_IN", entry.get("entryType").asString());
            assertEquals(7, entry.size());
        }
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // ---- HTTP ----

    private static List<String> ids(JsonNode data) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : data.get("items")) {
            ids.add(item.get("transactionId").asString());
        }
        return ids;
    }

    private JsonNode periodData(String referenceType, String from, String to, String cursor, Integer size)
            throws Exception {
        HttpResponse<String> response = period(referenceType, from, to, cursor, size);
        assertEquals(200, response.statusCode(), response.body());
        return jsonMapper.readTree(response.body()).get("data");
    }

    private HttpResponse<String> period(String referenceType, String from, String to, String cursor, Integer size)
            throws Exception {
        StringBuilder path = new StringBuilder(PERIOD)
                .append("?referenceType=").append(enc(referenceType))
                .append("&from=").append(enc(from))
                .append("&to=").append(enc(to));
        if (cursor != null) {
            path.append("&cursor=").append(enc(cursor));
        }
        if (size != null) {
            path.append("&size=").append(size);
        }
        return get(path.toString());
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String errorCode(HttpResponse<String> response) throws Exception {
        return jsonMapper.readTree(response.body()).get("error").get("code").asString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

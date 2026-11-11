package com.travelpay.ledger;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 원장 동시성 테스트용 관측 도구. 앱 커넥션 풀 밖의 연결로 계정 잠금을 쥐고, 다른 연결로 pg_stat_activity를 본다.
 * <ul>
 *     <li>앱 연결은 테스트 속성 ApplicationName={@value #APP_NAME}, 잠금을 쥐는 연결은 {@value #HOLDER_NAME}</li>
 *     <li>"잠금 대기 진입" 조건: 앱 연결 + wait_event_type='Lock' + ledger_accounts 대상 FOR (NO KEY) UPDATE 문장 +
 *         pg_blocking_pids에 잠금을 쥔 연결 또는 이미 대기 중인 앱 연결이 있음</li>
 *     <li>50ms 간격 샘플은 실패 메시지와 대기 샘플 수 기록에 쓴다 (짧은 대기는 놓칠 수 있어 단언에는 쓰지 않음)</li>
 * </ul>
 */
final class LedgerLockProbe implements AutoCloseable {

    static final String APP_NAME = "ledger-app-test";
    static final String HOLDER_NAME = "ledger-test-holder";
    private static final String OBSERVER_NAME = "ledger-test-observer";
    private static final Pattern ACCOUNT_LOCK_QUERY =
            Pattern.compile("(?is).*\\bledger_accounts\\b.*\\bfor\\s+(no\\s+key\\s+)?update\\b.*");
    private static final Pattern LOCK_CLAUSE = Pattern.compile("(?i)\\bfor\\s+(no\\s+key\\s+)?update\\b");
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(50);
    /** 가장 먼저 대기에 들어간 요청의 xact_start부터 이 시간 안에 풀어야 한다 (트랜잭션 제한 3초에 걸리기 전) */
    static final Duration RELEASE_WITHIN = Duration.ofSeconds(1);

    private final String url;
    private final String username;
    private final String password;
    private final Connection observer;
    private final List<Sample> samples = new CopyOnWriteArrayList<>();
    private final List<Connection> holders = new ArrayList<>();
    private final Set<Integer> holderPids = new HashSet<>();
    private ScheduledExecutorService sampler;

    LedgerLockProbe(String url, String username, String password) throws SQLException {
        this.url = url;
        this.username = username;
        this.password = password;
        this.observer = open(OBSERVER_NAME);
    }

    /** 앱 풀 밖 연결. autocommit은 끈다 */
    Connection open(String applicationName) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", applicationName);
        Connection connection = DriverManager.getConnection(url, properties);
        connection.setAutoCommit(false);
        return connection;
    }

    /** 새 잠금 연결로 계정 행을 FOR UPDATE로 잠근다. 반환한 연결은 {@link #release}로 푼다 */
    Connection holdAccount(UUID accountId) throws SQLException {
        Connection holder = open(HOLDER_NAME);
        try (PreparedStatement statement = holder.prepareStatement(
                "SELECT id FROM ledger_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, accountId);
            statement.executeQuery().close();
        }
        holders.add(holder);
        holderPids.add(pid(holder));
        return holder;
    }

    void release(Connection holder) throws SQLException {
        holder.rollback();
    }

    void startSampling() {
        sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ledger-lock-sampler");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleAtFixedRate(() -> {
            try {
                samples.addAll(snapshot());
            } catch (SQLException ignored) {
                // 샘플은 기록용이라 한 번 실패해도 다음 주기에 다시 본다
            }
        }, 0, SAMPLE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 앱 연결 count개가 위 조건으로 잠금 대기에 들어갈 때까지 기다린다. 시간 안에 안 되면 실패 메시지로 던진다 */
    List<Sample> awaitWaiting(int count, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Sample> waiting = List.of();
        while (System.nanoTime() < deadline) {
            waiting = waitingNow();
            if (waiting.size() >= count) {
                return waiting;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("잠금 대기 진입 시간 초과: 기대 " + count + "건, 실제 " + waiting.size() + "건\n"
                + describeSamples());
    }

    /**
     * 가장 먼저 대기에 들어간 요청의 xact_start + 1초 안이면 풀고 true. 넘었으면 풀지 않고 타이밍 조건 미충족으로 던진다.
     * 비교는 DB 시계(clock_timestamp)로 해 클라이언트·DB 시계 차이가 끼지 않게 한다
     */
    Release releaseInTime(Connection holder, List<Sample> waiting) throws Exception {
        Instant firstXactStart = waiting.stream().map(Sample::xactStart).min(Instant::compareTo).orElseThrow();
        Instant dbNow = dbNow();
        if (Duration.between(firstXactStart, dbNow).compareTo(RELEASE_WITHIN) > 0) {
            throw new AssertionError("타이밍 조건 미충족: 첫 대기 요청 xact_start=" + firstXactStart + " 해제 시도(DB 시각)="
                    + dbNow + " (허용 " + RELEASE_WITHIN.toMillis() + "ms)\n" + describeSamples());
        }
        release(holder);
        Set<String> clauses = waiting.stream().map(Sample::lockClause)
                .collect(Collectors.toCollection(TreeSet::new));
        return new Release(waiting.size(), clauses, firstXactStart, dbNow);
    }

    /**
     * 대기 확인 뒤 잠금 해제 기록. 통과해도 남도록 표준 출력에 한 줄로 적는다 (테스트 결과 XML의 system-out).
     * clauses: 대기 확인 때 pg_stat_activity의 query에서 잡힌 잠금 절. elapsed: 첫 대기 요청 xact_start → 해제 시도(DB 시각)
     */
    record Release(int waiting, Set<String> clauses, Instant firstXactStart, Instant releasedAt) {

        long elapsedMs() {
            return Duration.between(firstXactStart, releasedAt).toMillis();
        }

        String describe(String label) {
            return "[lock-probe] " + label + " waiting=" + waiting + " clauses=" + clauses
                    + " firstXactStart->release=" + elapsedMs() + "ms (limit " + RELEASE_WITHIN.toMillis()
                    + "ms, margin " + (RELEASE_WITHIN.toMillis() - elapsedMs()) + "ms)";
        }
    }

    List<Sample> waitingNow() throws SQLException {
        List<Sample> rows = snapshot();
        Set<Integer> blockers = new HashSet<>(holderPids);
        List<Sample> waiting = new ArrayList<>();
        boolean added = true;
        while (added) {
            added = false;
            for (Sample row : rows) {
                if (!waiting.contains(row) && row.isAccountLockWait()
                        && row.blockingPids().stream().anyMatch(blockers::contains)) {
                    waiting.add(row);
                    blockers.add(row.pid());
                    added = true;
                }
            }
        }
        return waiting;
    }

    /** 샘플 중 잠금 대기(위 조건의 문장·Lock 대기)였던 샘플 수. 기록용 */
    long lockWaitSampleCount() {
        return samples.stream().filter(Sample::isAccountLockWait).count();
    }

    List<Sample> samples() {
        return List.copyOf(samples);
    }

    String describeSamples() {
        return samples.stream()
                .filter(sample -> sample.waitEventType() != null || sample.isAccountLockQuery())
                .map(Sample::toString)
                .collect(Collectors.joining("\n", "pg_stat_activity 샘플(대기·잠금 문장만, " + samples.size()
                        + "건 중):\n", ""));
    }

    Instant dbNow() throws SQLException {
        try (PreparedStatement statement = observer.prepareStatement("SELECT clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            Instant now = resultSet.getTimestamp(1).toInstant();
            observer.commit();
            return now;
        }
    }

    private List<Sample> snapshot() throws SQLException {
        synchronized (observer) {
            List<Sample> rows = new ArrayList<>();
            try (PreparedStatement statement = observer.prepareStatement("""
                    SELECT clock_timestamp(), pid, xact_start, query_start, wait_event_type, wait_event, query,
                           pg_blocking_pids(pid)
                      FROM pg_stat_activity
                     WHERE application_name = ?
                    """)) {
                statement.setString(1, APP_NAME);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new Sample(instant(resultSet.getTimestamp(1)), resultSet.getInt(2),
                                instant(resultSet.getTimestamp(3)), instant(resultSet.getTimestamp(4)),
                                resultSet.getString(5), resultSet.getString(6), resultSet.getString(7),
                                pids(resultSet.getArray(8))));
                    }
                }
            }
            observer.commit();
            return rows;
        }
    }

    private static Set<Integer> pids(Array array) throws SQLException {
        Set<Integer> pids = new HashSet<>();
        if (array != null) {
            for (Object pid : (Object[]) array.getArray()) {
                pids.add(((Number) pid).intValue());
            }
        }
        return pids;
    }

    private static int pid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public void close() throws Exception {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler.awaitTermination(1, TimeUnit.SECONDS);
        }
        for (Connection holder : holders) {
            holder.rollback();
            holder.close();
        }
        synchronized (observer) {
            observer.close();
        }
    }

    /** pg_stat_activity 한 행 */
    record Sample(Instant at, int pid, Instant xactStart, Instant queryStart, String waitEventType, String waitEvent,
                  String query, Set<Integer> blockingPids) {

        boolean isAccountLockQuery() {
            return query != null && ACCOUNT_LOCK_QUERY.matcher(query).matches();
        }

        /** query에서 잡힌 잠금 절을 대문자로 (FOR UPDATE / FOR NO KEY UPDATE). 없으면 "-" */
        String lockClause() {
            if (query == null) {
                return "-";
            }
            Matcher matcher = LOCK_CLAUSE.matcher(query);
            return matcher.find() ? matcher.group().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT) : "-";
        }

        boolean isAccountLockWait() {
            return "Lock".equals(waitEventType) && isAccountLockQuery();
        }

        @Override
        public String toString() {
            return "at=" + at + " pid=" + pid + " xact_start=" + xactStart + " query_start=" + queryStart
                    + " wait=" + waitEventType + "/" + waitEvent + " blockedBy=" + blockingPids
                    + " query=" + (query == null ? null : query.replaceAll("\\s+", " "));
        }
    }
}

package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.application.payment.ManualReviewLock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL 세션 advisory lock으로 MANUAL_REVIEW 자동 처리를 한 서버에서만 실행합니다.
 * <ul>
 *     <li>pg_try_advisory_lock은 기다리지 않고 바로 결과를 돌려주므로, 다른 서버가 처리 중이면 이번 차례는 건너뜁니다.</li>
 *     <li>잠금은 이 연결(세션)에 걸리므로 처리하는 동안 연결 하나를 쥐고, 끝나면 같은 연결에서 풉니다.
 *         처리 안의 조회·반영은 각자 다른 연결의 트랜잭션으로 실행됩니다.</li>
 *     <li>서버가 처리 중에 죽으면 연결이 끊기면서 잠금도 풀리므로 만료 시간을 둘 필요가 없습니다.</li>
 *     <li>advisory lock은 DB 전체에서 공유됩니다. 단일 64비트 키를 쓰며, 두 32비트 키를 쓰는 환전 예약 잠금과는 키 공간이 겹치지 않습니다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PostgresManualReviewLock implements ManualReviewLock {

    /** 결제 MANUAL_REVIEW 자동 처리 전용 키 */
    static final long LOCK_KEY = 0x50_41_59_4D_52_56_00_01L;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean runExclusively(Runnable task) {
        Boolean ran = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            if (!call(connection, "select pg_try_advisory_lock(?)")) {
                return false;
            }
            try {
                task.run();
            } finally {
                call(connection, "select pg_advisory_unlock(?)");
            }
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}

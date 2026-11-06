package com.travelpay.payment.transfer.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.travelpay.payment.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 송금 상태 전이표를 실제 PostgreSQL로 고정합니다.
 * 상태를 바꾸는 쓰기 경로는 {@link TransferRepository}의 조건부 UPDATE뿐이며, DB에는 전이 자체를 막는 제약이 없고
 * (CHECK는 상태와 사유 컬럼의 조합만 검사) 각 UPDATE의 WHERE 조건이 허용 전이를 정합니다.
 * 모든 조건부 UPDATE × 모든 시작 상태를 실행해, 허용된 시작 상태에서만 1행이 바뀌고 그 밖에는 행이 그대로인지 확인합니다.
 * Testcontainers PostgreSQL을 사용하며 Docker가 없으면 건너뜁니다. 각 테스트는 롤백됩니다.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "transfer.recovery.enabled=false"})
@Transactional
class TransferStatusTransitionIntegrationTest extends PostgresContainerSupport {

    private static final String REQUEST_HASH = "0".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    /** 시작 시도 횟수입니다. 0이 아니어야 시도 횟수 +1(결과 모름)과 0으로 되돌림(재처리)을 구분할 수 있습니다. */
    private static final int START_ATTEMPTS = 3;

    /** 시작 상태별로 CHECK 제약(실패 사유는 FAILED만, 검토 사유는 MANUAL_REVIEW만)을 지키는 사유 값입니다. */
    private static final Map<TransferStatus, String[]> REASONS = Map.of(
            TransferStatus.FAILED, new String[] {"INSUFFICIENT_BALANCE", null},
            TransferStatus.MANUAL_REVIEW, new String[] {null, "RETRY_EXHAUSTED"});

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 상태를 바꾸는 조건부 UPDATE와 그 허용 시작 상태·결과입니다. */
    enum Operation {
        CANCEL(TransferStatus.REQUESTED, TransferStatus.CANCELLED, null, null, START_ATTEMPTS) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.cancelRequested(transfer.getId(), transfer.getSenderId(), NOW);
            }
        },
        FINISH_COMPLETED(TransferStatus.PROCESSING, TransferStatus.COMPLETED, null, null, START_ATTEMPTS) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.finishProcessing(transfer.getId(), TransferStatus.COMPLETED, null, NOW);
            }
        },
        FINISH_FAILED(TransferStatus.PROCESSING, TransferStatus.FAILED, "ACCOUNT_UNAVAILABLE", null, START_ATTEMPTS) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.finishProcessing(transfer.getId(), TransferStatus.FAILED,
                        TransferFailureReason.ACCOUNT_UNAVAILABLE, NOW);
            }
        },
        CLAIM_FOR_RECOVERY(TransferStatus.PROCESSING, TransferStatus.PROCESSING, null, null, START_ATTEMPTS) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.claimForRecovery(transfer.getId(), transfer.getUpdatedAt(), NOW);
            }
        },
        RECORD_UNKNOWN(TransferStatus.PROCESSING, TransferStatus.PROCESSING, null, null, START_ATTEMPTS + 1) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.recordUnknownAttempt(transfer.getId(), transfer.getAttemptCount(),
                        TransferStatus.PROCESSING, null, NOW);
            }
        },
        RECORD_UNKNOWN_EXHAUSTED(TransferStatus.PROCESSING, TransferStatus.MANUAL_REVIEW, null, "RETRY_EXHAUSTED",
                START_ATTEMPTS + 1) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.recordUnknownAttempt(transfer.getId(), transfer.getAttemptCount(), TransferStatus.MANUAL_REVIEW,
                        TransferReviewReason.RETRY_EXHAUSTED, NOW);
            }
        },
        MARK_MANUAL_REVIEW(TransferStatus.PROCESSING, TransferStatus.MANUAL_REVIEW, null,
                "LEDGER_REQUEST_NOT_ACCEPTED", START_ATTEMPTS) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.markManualReview(transfer.getId(),
                        TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, NOW);
            }
        },
        RESOLVE_COMPLETED(TransferStatus.MANUAL_REVIEW, TransferStatus.COMPLETED, null, null, START_ATTEMPTS) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.resolveManualReview(transfer.getId(), TransferStatus.COMPLETED, null,
                        transfer.getAttemptCount(), NOW);
            }
        },
        RESOLVE_FAILED(TransferStatus.MANUAL_REVIEW, TransferStatus.FAILED, "NOT_PROCESSED", null, START_ATTEMPTS) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.resolveManualReview(transfer.getId(), TransferStatus.FAILED,
                        TransferFailureReason.NOT_PROCESSED, transfer.getAttemptCount(), NOW);
            }
        },
        RESOLVE_RETRY(TransferStatus.MANUAL_REVIEW, TransferStatus.PROCESSING, null, null, 0) {
            @Override
            int apply(TransferRepository repository, Transfer transfer) {
                return repository.resolveManualReview(transfer.getId(), TransferStatus.PROCESSING, null, 0, NOW);
            }
        };

        final TransferStatus from;
        final TransferStatus to;
        final String failureReason;
        final String reviewReason;
        final int attemptCount;

        Operation(TransferStatus from, TransferStatus to, String failureReason, String reviewReason,
                int attemptCount) {
            this.from = from;
            this.to = to;
            this.failureReason = failureReason;
            this.reviewReason = reviewReason;
            this.attemptCount = attemptCount;
        }

        abstract int apply(TransferRepository repository, Transfer transfer);
    }

    static Stream<Arguments> everyOperationFromEveryStatus() {
        return Arrays.stream(Operation.values()).flatMap(operation -> Arrays.stream(TransferStatus.values())
                .map(status -> Arguments.of(operation, status)));
    }

    /**
     * 허용된 시작 상태: 1행이 바뀌고 결과 상태·사유·시도 횟수가 전이표와 같습니다(CHECK도 통과).
     * 시도 횟수는 결과 모름 기록만 +1, 운영자 재처리(PROCESSING)만 0으로 되돌리고 나머지는 그대로입니다.
     * 그 밖의 시작 상태: 0행이며 상태·사유·시도 횟수·수정 시각이 모두 그대로입니다.
     * 따라서 COMPLETED·FAILED·CANCELLED는 어떤 경로로도 바뀌지 않는 종료 상태이고,
     * REQUESTED는 취소만, MANUAL_REVIEW는 운영자 확정만 받습니다.
     */
    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("everyOperationFromEveryStatus")
    void conditionalUpdateChangesOnlyAllowedSourceStatus(Operation operation, TransferStatus from) {
        Transfer transfer = savedIn(from);
        Row before = row(transfer.getId());

        int updated = operation.apply(transferRepository, transfer);

        Row after = row(transfer.getId());
        if (from == operation.from) {
            assertEquals(1, updated);
            assertEquals(operation.to.name(), after.status());
            assertEquals(operation.failureReason, after.failureReason());
            assertEquals(operation.reviewReason, after.reviewReason());
            assertEquals(operation.attemptCount, after.attemptCount());
            assertEquals(NOW, after.updatedAt());
        } else {
            assertEquals(0, updated);
            assertEquals(before, after);
        }
    }

    /** 원하는 상태의 송금을 만듭니다. 생성 경로는 REQUESTED·PROCESSING뿐이므로 나머지는 SQL로 상태를 맞춥니다. */
    private Transfer savedIn(TransferStatus status) {
        Transfer saved = transferRepository.saveAndFlush(Transfer.process(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1_000L, "KRW", null, "transition", REQUEST_HASH));
        String[] reasons = REASONS.getOrDefault(status, new String[] {null, null});
        jdbcTemplate.update(
                "update transfer set status = ?, failure_reason = ?, review_reason = ?, attempt_count = ? where id = ?",
                status.name(), reasons[0], reasons[1], START_ATTEMPTS, saved.getId());
        entityManager.clear();
        return transferRepository.findById(saved.getId()).orElseThrow();
    }

    private Row row(UUID transferId) {
        entityManager.clear();
        return jdbcTemplate.queryForObject(
                "select status, failure_reason, review_reason, attempt_count, updated_at from transfer where id = ?",
                (rs, rowNum) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getObject(5, java.time.OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MICROS)),
                transferId);
    }

    private record Row(String status, String failureReason, String reviewReason, int attemptCount, Instant updatedAt) {
    }
}

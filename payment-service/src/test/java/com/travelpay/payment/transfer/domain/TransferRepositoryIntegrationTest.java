package com.travelpay.payment.transfer.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.support.PostgresContainerSupport;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL에 Flyway 마이그레이션을 적용한 뒤 엔티티 매핑(validate), 제약 조건, 목록 쿼리를 검증합니다.
 * Testcontainers PostgreSQL을 사용하며 Docker가 없으면 건너뜁니다.
 * 각 테스트는 트랜잭션 안에서 실행 후 롤백되며, 무작위 회원 ID를 사용해 기존 데이터의 영향을 받지 않습니다.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "transfer.recovery.enabled=false"})
@Transactional
class TransferRepositoryIntegrationTest extends PostgresContainerSupport {


    // PostgreSQL의 uuid 비교(바이트 순서)와 같도록 소문자 문자열로 비교합니다. UUID.compareTo는 부호 있는 비교라 다릅니다.
    private static final Comparator<Transfer> NEWEST_FIRST = Comparator
            .comparing(Transfer::getCreatedAt)
            .thenComparing(transfer -> transfer.getId().toString())
            .reversed();

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savedTransferIsReadBackWithSameValues() {
        UUID senderId = UUID.randomUUID();
        Transfer saved = transferRepository.saveAndFlush(transfer(senderId, UUID.randomUUID(), "key-1", 10_000L));
        entityManager.clear();

        Transfer found = transferRepository.findBySenderIdAndIdempotencyKey(senderId, "key-1").orElseThrow();

        assertEquals(saved.getId(), found.getId());
        assertEquals("KRW", found.getCurrency());
        assertEquals(TransferStatus.REQUESTED, found.getStatus());
        // 접수 응답의 생성 시각이 이후 조회한 DB 저장 값(마이크로초)과 정확히 같아야 합니다.
        assertEquals(saved.getCreatedAt(), found.getCreatedAt());
    }

    @Test
    void sameSenderAndIdempotencyKeyViolatesUniqueConstraint() {
        UUID senderId = UUID.randomUUID();
        transferRepository.saveAndFlush(transfer(senderId, UUID.randomUUID(), "dup-key", 10_000L));

        assertThrows(DataIntegrityViolationException.class, () ->
                transferRepository.saveAndFlush(transfer(senderId, UUID.randomUUID(), "dup-key", 20_000L)));
    }

    @Test
    void sameIdempotencyKeyIsAllowedForDifferentSenders() {
        transferRepository.saveAndFlush(transfer(UUID.randomUUID(), UUID.randomUUID(), "shared-key", 10_000L));
        transferRepository.saveAndFlush(transfer(UUID.randomUUID(), UUID.randomUUID(), "shared-key", 10_000L));
    }

    @Test
    void nonPositiveAmountViolatesCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class, () ->
                transferRepository.saveAndFlush(transfer(UUID.randomUUID(), UUID.randomUUID(), "zero", 0L)));
    }

    @Test
    void pagesReturnAllRelatedTransfersInOrderWithoutDuplicates() {
        UUID userId = UUID.randomUUID();
        UUID otherA = UUID.randomUUID();
        UUID otherB = UUID.randomUUID();
        Instant base = Instant.parse("2026-10-28T01:00:00.123456Z");

        // 같은 생성 시각 3건을 포함해 ID 순서로 이어지는지 확인합니다.
        List<Transfer> related = new ArrayList<>();
        related.add(saveAt(transfer(userId, otherA, "s1", 1_000L), base));
        related.add(saveAt(transfer(userId, otherB, "s2", 2_000L), base));
        related.add(saveAt(transfer(otherA, userId, "r1", 3_000L), base));
        related.add(saveAt(transfer(otherB, userId, "r2", 4_000L), base.plusSeconds(1)));
        related.add(saveAt(transfer(userId, otherA, "s3", 5_000L), base.minusSeconds(1)));
        // 다른 회원끼리의 송금은 목록에 포함되면 안 됩니다.
        saveAt(transfer(otherA, otherB, "x1", 6_000L), base);
        entityManager.clear();

        List<UUID> expected = related.stream().sorted(NEWEST_FIRST).map(Transfer::getId).toList();

        assertEquals(expected, collectAllPages(userId, null, 2));
    }

    @Test
    void directionFilterReturnsOnlySentOrReceived() {
        UUID userId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Instant base = Instant.parse("2026-10-28T01:00:00Z");

        Transfer sent1 = saveAt(transfer(userId, otherId, "s1", 1_000L), base);
        Transfer sent2 = saveAt(transfer(userId, otherId, "s2", 1_000L), base.plusSeconds(1));
        Transfer received = saveAt(transfer(otherId, userId, "r1", 1_000L), base.plusSeconds(2));
        entityManager.clear();

        assertEquals(List.of(sent2.getId(), sent1.getId()), collectAllPages(userId, TransferDirection.SENT, 1));
        assertEquals(List.of(received.getId()), collectAllPages(userId, TransferDirection.RECEIVED, 1));
    }

    @Test
    void countsOnlyRelatedTransfers() {
        UUID userId = UUID.randomUUID();
        transferRepository.saveAndFlush(transfer(userId, UUID.randomUUID(), "only", 1_000L));
        transferRepository.saveAndFlush(transfer(UUID.randomUUID(), UUID.randomUUID(), "other", 1_000L));
        entityManager.clear();

        PageResponse<TransferResponse> page = transferService.list(userId, null, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals(1, page.totalElements());
        assertEquals(1, page.totalPages());
    }

    @Test
    void cancelChangesRequestedTransferOnceAndKeepsCancelledOnRetry() {
        UUID senderId = UUID.randomUUID();
        Transfer saved = transferRepository.saveAndFlush(transfer(senderId, UUID.randomUUID(), "cancel", 1_000L));

        TransferResponse first = transferService.cancel(senderId, saved.getId());
        TransferResponse retry = transferService.cancel(senderId, saved.getId());

        assertEquals(TransferStatus.CANCELLED, first.status());
        assertEquals(TransferStatus.CANCELLED, retry.status());
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "select status from transfer where id = ?", String.class, saved.getId()));
    }

    @Test
    void receiverAndOtherUserCannotCancelAndStatusStaysRequested() {
        UUID receiverId = UUID.randomUUID();
        Transfer saved = transferRepository.saveAndFlush(transfer(UUID.randomUUID(), receiverId, "keep", 1_000L));

        var receiver = assertThrows(BusinessException.class,
                () -> transferService.cancel(receiverId, saved.getId()));
        var otherUser = assertThrows(BusinessException.class,
                () -> transferService.cancel(UUID.randomUUID(), saved.getId()));

        assertSame(TransferErrorCode.NOT_FOUND, receiver.getErrorCode());
        assertSame(TransferErrorCode.NOT_FOUND, otherUser.getErrorCode());
        assertEquals("REQUESTED", jdbcTemplate.queryForObject(
                "select status from transfer where id = ?", String.class, saved.getId()));
    }

    @Test
    void unknownStatusViolatesCheckConstraint() {
        Transfer saved = transferRepository.saveAndFlush(transfer(UUID.randomUUID(), UUID.randomUUID(), "ck", 1_000L));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update transfer set status = 'UNKNOWN_STATUS' where id = ?", saved.getId()));
    }

    /** PostgreSQL은 제약 위반 후 트랜잭션을 더 쓸 수 없으므로 위반 하나씩 따로 실행합니다. */
    @ParameterizedTest
    @CsvSource(value = {"FAILED, NULL", "COMPLETED, INSUFFICIENT_BALANCE", "FAILED, UNKNOWN_REASON"}, nullValues = "NULL")
    void failureReasonIsRequiredOnlyForFailedTransfer(String status, String failureReason) {
        Transfer saved = transferRepository.saveAndFlush(processing(UUID.randomUUID(), "reason"));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update transfer set status = ?, failure_reason = ? where id = ?", status, failureReason, saved.getId()));
    }

    @Test
    void finishProcessingUpdatesOnlyProcessingTransferOnce() {
        Transfer saved = transferRepository.saveAndFlush(processing(UUID.randomUUID(), "finish"));
        Instant now = Instant.now();

        int first = transferRepository.finishProcessing(saved.getId(), TransferStatus.FAILED,
                TransferFailureReason.INSUFFICIENT_BALANCE, now);
        int second = transferRepository.finishProcessing(saved.getId(), TransferStatus.COMPLETED, null, now);

        assertEquals(1, first);
        assertEquals(0, second);
        Transfer found = transferRepository.findById(saved.getId()).orElseThrow();
        assertEquals(TransferStatus.FAILED, found.getStatus());
        assertEquals(TransferFailureReason.INSUFFICIENT_BALANCE, found.getFailureReason());
    }

    /**
     * 기존 로컬 데이터와 섞이지 않도록 2000년 시각을 쓰고, 대기 시간(30초 × 2^max(attempt_count - 1, 0))이 지난 처리 중 송금만
     * 선점하는지, 선점한 송금은 수정 시각이 선점 시각으로 바뀌어 다시 선점되지 않는지 확인합니다.
     */
    @Test
    void claimRecoveryTargetsClaimsOnlyDueProcessingTransfersOnce() {
        Instant base = Instant.parse("2000-01-01T00:00:00Z");
        Transfer firstTry = saveWithTimes(processing(UUID.randomUUID(), "due-first"), base, base.plusSeconds(1));
        Transfer fourthTry = saveWithTimes(processing(UUID.randomUUID(), "due-fourth"), base, base);
        jdbcTemplate.update("update transfer set attempt_count = 3 where id = ?", fourthTry.getId());
        Transfer requested = saveWithTimes(transfer(UUID.randomUUID(), UUID.randomUUID(), "due-requested", 1_000L),
                base, base);
        Set<UUID> created = Set.of(firstTry.getId(), fourthTry.getId(), requested.getId());
        Instant claimedAt = Instant.parse("2026-11-02T00:00:00Z");
        entityManager.clear();

        // 30초 뒤: 첫 시도만 대기 시간이 지났습니다(3회 시도한 송금은 2분 대기).
        List<UUID> afterThirtySeconds = ids(transferRepository.claimRecoveryTargets(base.plusSeconds(32), claimedAt, 100),
                created);
        // 2분 뒤: 이미 선점한 첫 시도는 대기 시간이 다시 시작돼 빠지고, 3회 시도한 송금만 선점합니다.
        List<UUID> afterTwoMinutes = ids(transferRepository.claimRecoveryTargets(base.plusSeconds(121), claimedAt, 100),
                created);
        entityManager.clear();

        assertEquals(List.of(firstTry.getId()), afterThirtySeconds);
        assertEquals(List.of(fourthTry.getId()), afterTwoMinutes);
        assertEquals(claimedAt, transferRepository.findById(firstTry.getId()).orElseThrow().getUpdatedAt());
        assertEquals(claimedAt, transferRepository.findById(fourthTry.getId()).orElseThrow().getUpdatedAt());
        assertEquals(base, transferRepository.findById(requested.getId()).orElseThrow().getUpdatedAt());
    }

    /** 선점 결과는 원래 수정 시각이 오래된 순이고 limit건까지이며, 반환한 송금은 선점 시각을 담고 있습니다. */
    @Test
    void claimRecoveryTargetsReturnsOldestFirstUpToLimit() {
        Instant base = Instant.parse("2000-01-01T00:00:00Z");
        Transfer newer = saveWithTimes(processing(UUID.randomUUID(), "order-newer"), base, base.plusSeconds(1));
        Transfer older = saveWithTimes(processing(UUID.randomUUID(), "order-older"), base, base);
        Set<UUID> created = Set.of(newer.getId(), older.getId());
        Instant claimedAt = Instant.parse("2026-11-02T00:00:00Z");
        entityManager.clear();

        List<Transfer> first = transferRepository.claimRecoveryTargets(base.plusSeconds(60), claimedAt, 1);
        List<Transfer> rest = transferRepository.claimRecoveryTargets(base.plusSeconds(60), claimedAt, 100);

        assertEquals(1, first.size());
        assertEquals(older.getId(), first.get(0).getId());
        assertEquals(claimedAt, first.get(0).getUpdatedAt());
        assertEquals(List.of(newer.getId()), ids(rest, created));
    }

    /**
     * 여러 건을 선점하면 반환 순서는 선점 전 (수정 시각, ID) 순입니다. 저장(물리) 순서를 수정 시각과 반대로 만들고,
     * 같은 수정 시각 두 건은 ID 순인지도 봅니다. 반환 목록 전체를 기대 순서와 그대로 비교하므로 UPDATE ... RETURNING이
     * 어떤 순서로 행을 내놓든 최종 정렬(order by p.updated_at, p.id)이 없으면 실패합니다.
     */
    @Test
    void claimRecoveryTargetsReturnsMultipleClaimsInUpdatedAtThenIdOrder() {
        Instant base = Instant.parse("2000-01-01T00:00:00Z");
        List<Transfer> saved = new ArrayList<>();
        // 수정 시각이 늦은 것부터 저장합니다: +3, +2, +1(두 건), +0
        saved.add(saveWithTimes(processing(UUID.randomUUID(), "multi-3"), base, base.plusSeconds(3)));
        saved.add(saveWithTimes(processing(UUID.randomUUID(), "multi-2"), base, base.plusSeconds(2)));
        saved.add(saveWithTimes(processing(UUID.randomUUID(), "multi-1a"), base, base.plusSeconds(1)));
        saved.add(saveWithTimes(processing(UUID.randomUUID(), "multi-1b"), base, base.plusSeconds(1)));
        saved.add(saveWithTimes(processing(UUID.randomUUID(), "multi-0"), base, base));
        Set<UUID> created = Set.copyOf(saved.stream().map(Transfer::getId).toList());
        List<UUID> expected = saved.stream()
                // PostgreSQL uuid는 바이트 순으로 비교하므로 문자열 비교와 같습니다(UUID.compareTo는 부호 있는 비교라 다름).
                .sorted(Comparator.comparing(Transfer::getUpdatedAt)
                        .thenComparing(transfer -> transfer.getId().toString()))
                .map(Transfer::getId)
                .toList();
        // 저장 순서가 기대 순서와 달라야 검증이 의미 있습니다.
        assertTrue(!expected.equals(saved.stream().map(Transfer::getId).toList()));
        Instant claimedAt = Instant.parse("2026-11-02T00:00:00Z");
        entityManager.clear();

        List<Transfer> claimed = transferRepository.claimRecoveryTargets(base.plusSeconds(60), claimedAt, 100);

        assertEquals(expected, ids(claimed, created));
        claimed.stream().filter(transfer -> created.contains(transfer.getId()))
                .forEach(transfer -> assertEquals(claimedAt, transfer.getUpdatedAt()));
    }

    /** 선점 확인: 상태가 처리 중이고 수정 시각이 선점 때 값과 같을 때만 갱신합니다. 다른 서버가 다시 선점하면(수정 시각이 바뀜) 0행입니다. */
    @Test
    void renewRecoveryClaimUpdatesOnlyWhenClaimIsUnchanged() {
        Instant base = Instant.parse("2000-01-01T00:00:00Z");
        Transfer processing = saveWithTimes(processing(UUID.randomUUID(), "renew"), base, base);
        Instant claimedAt = Instant.parse("2026-11-02T00:00:00.123456Z");
        Instant reclaimedAt = claimedAt.plusSeconds(31);
        jdbcTemplate.update("update transfer set updated_at = ? where id = ?",
                claimedAt.atOffset(ZoneOffset.UTC), processing.getId());

        int mine = transferRepository.renewRecoveryClaim(processing.getId(), claimedAt, claimedAt.plusMillis(1));
        // 다른 서버가 다시 선점한 뒤에는 처음 선점 시각으로 확인해도 0행입니다.
        jdbcTemplate.update("update transfer set updated_at = ? where id = ?",
                reclaimedAt.atOffset(ZoneOffset.UTC), processing.getId());
        int stale = transferRepository.renewRecoveryClaim(processing.getId(), claimedAt, reclaimedAt.plusMillis(1));
        // 1마이크로초만 달라도 다른 선점으로 봅니다.
        int offByMicro = transferRepository.renewRecoveryClaim(processing.getId(), reclaimedAt.plusNanos(1_000),
                reclaimedAt.plusMillis(1));

        assertEquals(List.of(1, 0, 0), List.of(mine, stale, offByMicro));
        assertEquals(reclaimedAt, transferRepository.findById(processing.getId()).orElseThrow().getUpdatedAt());
    }

    @Test
    void recordUnknownAttemptCountsOnceAndMovesToManualReviewAtLimit() {
        Transfer saved = transferRepository.saveAndFlush(processing(UUID.randomUUID(), "unknown"));
        Instant now = Instant.parse("2026-11-02T00:00:00Z");

        int first = transferRepository.recordUnknownAttempt(saved.getId(), 0, TransferStatus.PROCESSING, null, now);
        int stale = transferRepository.recordUnknownAttempt(saved.getId(), 0, TransferStatus.PROCESSING, null, now);
        int exhausted = transferRepository.recordUnknownAttempt(saved.getId(), 1, TransferStatus.MANUAL_REVIEW,
                TransferReviewReason.RETRY_EXHAUSTED, now);
        int afterReview = transferRepository.markManualReview(saved.getId(),
                TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, now);

        assertEquals(List.of(1, 0, 1, 0), List.of(first, stale, exhausted, afterReview));
        Transfer found = transferRepository.findById(saved.getId()).orElseThrow();
        assertEquals(2, found.getAttemptCount());
        assertEquals(TransferStatus.MANUAL_REVIEW, found.getStatus());
        assertEquals(TransferReviewReason.RETRY_EXHAUSTED, found.getReviewReason());
    }

    @Test
    void markManualReviewChangesOnlyProcessingTransfer() {
        Transfer processing = transferRepository.saveAndFlush(processing(UUID.randomUUID(), "review"));
        Transfer requested = transferRepository.saveAndFlush(
                transfer(UUID.randomUUID(), UUID.randomUUID(), "review-requested", 1_000L));
        Instant now = Instant.parse("2026-11-02T00:00:00Z");

        int processingUpdated = transferRepository.markManualReview(processing.getId(),
                TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, now);
        int requestedUpdated = transferRepository.markManualReview(requested.getId(),
                TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, now);

        assertEquals(1, processingUpdated);
        assertEquals(0, requestedUpdated);
        Transfer found = transferRepository.findById(processing.getId()).orElseThrow();
        assertEquals(TransferStatus.MANUAL_REVIEW, found.getStatus());
        assertEquals(TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, found.getReviewReason());
    }

    /** PostgreSQL은 제약 위반 후 트랜잭션을 더 쓸 수 없으므로 위반 하나씩 따로 실행합니다. */
    @ParameterizedTest
    @CsvSource(value = {"MANUAL_REVIEW, NULL", "PROCESSING, RETRY_EXHAUSTED", "MANUAL_REVIEW, UNKNOWN_REASON"},
            nullValues = "NULL")
    void reviewReasonIsRequiredOnlyForManualReviewTransfer(String status, String reviewReason) {
        Transfer saved = transferRepository.saveAndFlush(processing(UUID.randomUUID(), "review-ck"));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update transfer set status = ?, review_reason = ? where id = ?", status, reviewReason, saved.getId()));
    }

    @Test
    void negativeAttemptCountViolatesCheckConstraint() {
        Transfer saved = transferRepository.saveAndFlush(processing(UUID.randomUUID(), "attempt-ck"));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update transfer set attempt_count = -1 where id = ?", saved.getId()));
    }

    private static List<UUID> ids(List<Transfer> transfers, Set<UUID> created) {
        return transfers.stream().map(Transfer::getId).filter(created::contains).toList();
    }

    @Test
    void countsAndListsProcessingTransfersCreatedBeforeGivenTime() {
        Instant base = Instant.parse("2000-01-01T00:00:00Z");
        long existing = transferRepository.countProcessingCreatedBefore(base.plusSeconds(5));
        Transfer second = saveWithTimes(processing(UUID.randomUUID(), "stuck-second"), base.plusSeconds(1), base);
        Transfer first = saveWithTimes(processing(UUID.randomUUID(), "stuck-first"), base, base);
        saveWithTimes(processing(UUID.randomUUID(), "stuck-recent"), base.plusSeconds(10), base);
        saveWithTimes(transfer(UUID.randomUUID(), UUID.randomUUID(), "stuck-requested", 1_000L), base, base);

        long count = transferRepository.countProcessingCreatedBefore(base.plusSeconds(5));
        List<UUID> ids = transferRepository.findProcessingIdsCreatedBefore(base.plusSeconds(5), PageRequest.of(0, 100));

        assertEquals(existing + 2, count);
        assertEquals(List.of(first.getId(), second.getId()), ids.stream()
                .filter(id -> id.equals(first.getId()) || id.equals(second.getId()))
                .toList());
    }

    private List<UUID> collectAllPages(UUID userId, TransferDirection direction, int size) {
        List<UUID> ids = new ArrayList<>();
        int pageNumber = 0;
        PageResponse<TransferResponse> page;
        do {
            page = transferService.list(userId, direction, pageNumber++, size);
            assertTrue(page.items().size() <= size);
            page.items().stream().map(TransferResponse::transferId).forEach(ids::add);
        } while (pageNumber < page.totalPages());
        return ids;
    }

    /** 생성 시각은 @PrePersist가 정하므로, 저장 후 SQL로 원하는 시각으로 바꿉니다. */
    private Transfer saveAt(Transfer transfer, Instant createdAt) {
        Transfer saved = transferRepository.saveAndFlush(transfer);
        jdbcTemplate.update("update transfer set created_at = ? where id = ?",
                createdAt.atOffset(ZoneOffset.UTC), saved.getId());
        entityManager.detach(saved);
        return transferRepository.findById(saved.getId()).orElseThrow();
    }

    /** 생성·수정 시각은 @PrePersist가 정하므로, 저장 후 SQL로 원하는 시각으로 바꿉니다. */
    private Transfer saveWithTimes(Transfer transfer, Instant createdAt, Instant updatedAt) {
        Transfer saved = transferRepository.saveAndFlush(transfer);
        jdbcTemplate.update("update transfer set created_at = ?, updated_at = ? where id = ?",
                createdAt.atOffset(ZoneOffset.UTC), updatedAt.atOffset(ZoneOffset.UTC), saved.getId());
        entityManager.detach(saved);
        return transferRepository.findById(saved.getId()).orElseThrow();
    }

    private static Transfer processing(UUID senderId, String idempotencyKey) {
        return Transfer.process(senderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1_000L, "KRW", null, idempotencyKey);
    }

    private static Transfer transfer(UUID senderId, UUID receiverId, String idempotencyKey, long amount) {
        return Transfer.request(
                senderId,
                receiverId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                amount,
                "KRW",
                null,
                idempotencyKey
        );
    }
}

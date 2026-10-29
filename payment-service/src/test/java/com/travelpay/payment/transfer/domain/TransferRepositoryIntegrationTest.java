package com.travelpay.payment.transfer.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL에 Flyway 마이그레이션을 적용한 뒤 엔티티 매핑(validate), 제약 조건, 목록 쿼리를 검증합니다.
 * 로컬 compose의 postgres를 사용하므로 PAYMENT_DB_URL 등 DB 환경 변수가 있을 때만 실행합니다.
 * 각 테스트는 트랜잭션 안에서 실행 후 롤백되며, 무작위 회원 ID를 사용해 기존 데이터의 영향을 받지 않습니다.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "PAYMENT_DB_URL", matches = ".+")
class TransferRepositoryIntegrationTest {

    private static final String REQUEST_HASH = "0".repeat(64);

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
        assertEquals(REQUEST_HASH, found.getRequestHash());
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
                "update transfer set status = 'COMPLETED' where id = ?", saved.getId()));
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

    private static Transfer transfer(UUID senderId, UUID receiverId, String idempotencyKey, long amount) {
        return Transfer.request(
                senderId,
                receiverId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                amount,
                "KRW",
                null,
                idempotencyKey,
                REQUEST_HASH
        );
    }
}

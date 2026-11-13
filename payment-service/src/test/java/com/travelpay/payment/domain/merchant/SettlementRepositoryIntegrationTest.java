package com.travelpay.payment.domain.merchant;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementCandidate;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import com.travelpay.payment.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산 테이블 매핑·정산 대상 조회·복구 조건부 업데이트를 실제 PostgreSQL(Flyway 적용)에서 검증합니다.
 * 컨텍스트가 뜨면 ddl-auto=validate로 Settlement·SettlementItem 매핑도 함께 확인됩니다.
 * 각 테스트는 트랜잭션 안에서 실행 후 롤백되며, 테스트마다 새 가맹점을 만들어 그 가맹점의 데이터로만 확인합니다.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "transfer.recovery.enabled=false",
        "payment.settlement-recovery.enabled=false"})
@Transactional
class SettlementRepositoryIntegrationTest extends PostgresContainerSupport {

    private static final Instant CUTOFF = Instant.parse("2026-11-04T00:00:00Z");

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private SettlementItemRepository settlementItemRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private SettlementReviewResolutionRepository resolutionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        Merchant merchant = Merchant.create("정산 가맹점", CurrencyCode.KRW, UUID.randomUUID());
        merchantId = merchantRepository.save(merchant).getId();
        entityManager.flush();
    }

    // 정산 대상 조회

    @Test
    void settlementTargetsAreApprovedPaymentsApprovedBeforeCutoff() {
        Payment old = approvedPayment(merchantId, CUTOFF.minus(Duration.ofHours(1)));
        Payment recent = approvedPayment(merchantId, CUTOFF.plus(Duration.ofHours(1)));
        Payment otherMerchant = approvedPayment(UUID.randomUUID(), CUTOFF.minus(Duration.ofHours(1)));

        List<UUID> ids = targetIds(CUTOFF);

        assertEquals(List.of(old.getId()), ids);
        assertFalse(ids.contains(recent.getId()));
        assertFalse(ids.contains(otherMerchant.getId()));
    }

    @Test
    void settlementTargetsExcludeNonApprovedPayments() {
        Payment requested = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        changeStatus(requested, PaymentStatus.REFUND_REQUESTED);
        Payment refundPending = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        changeStatus(refundPending, PaymentStatus.REFUND_PENDING);
        Payment refunded = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        changeStatus(refunded, PaymentStatus.REFUNDED);

        assertTrue(targetIds(CUTOFF).isEmpty());
    }

    @Test
    void approvalByManualReviewResolutionCountsAsApproval() {
        Payment payment = savePayment(merchantId, PaymentStatus.APPROVED);
        saveHistory(payment.getId(), PaymentStatus.PENDING, PaymentStatus.MANUAL_REVIEW, PaymentActor.SYSTEM,
                CUTOFF.minus(Duration.ofDays(2)));
        saveHistory(payment.getId(), PaymentStatus.MANUAL_REVIEW, PaymentStatus.APPROVED, PaymentActor.MASTER,
                CUTOFF.minus(Duration.ofDays(1)));

        assertEquals(List.of(payment.getId()), targetIds(CUTOFF));
    }

    @Test
    void returningToApprovedAfterRefundOrRejectIsNotApprovalTime() {
        // 기준 뒤에 처음 승인됐고, 그 전의 → APPROVED는 부분 환불·신청 거절뿐인 경우는 대상이 아님
        Payment payment = savePayment(merchantId, PaymentStatus.APPROVED);
        saveHistory(payment.getId(), PaymentStatus.REFUND_PENDING, PaymentStatus.APPROVED, PaymentActor.SYSTEM,
                CUTOFF.minus(Duration.ofDays(2)));
        saveHistory(payment.getId(), PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED, PaymentActor.MERCHANT,
                CUTOFF.minus(Duration.ofDays(1)));
        saveHistory(payment.getId(), PaymentStatus.PENDING, PaymentStatus.APPROVED, PaymentActor.SYSTEM,
                CUTOFF.plus(Duration.ofHours(1)));

        assertTrue(targetIds(CUTOFF).isEmpty());
    }

    @Test
    void settlementTargetsExcludeAlreadySettledPayments() {
        Payment settled = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        Payment notSettled = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        settlementItemRepository.saveAll(List.of(SettlementItem.of(settled.getId(), settlement.getId(), 10_000L)));

        assertEquals(List.of(notSettled.getId()), targetIds(CUTOFF));
    }

    // 정산 미리보기 조회

    @Test
    void settlementCandidatesIncludeAllUnsettledApprovedPaymentsWithApprovalTime() {
        Payment old = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        Payment recent = approvedPayment(merchantId, CUTOFF.plus(Duration.ofHours(1)));
        Payment reviewed = savePayment(merchantId, PaymentStatus.APPROVED);
        saveHistory(reviewed.getId(), PaymentStatus.PENDING, PaymentStatus.MANUAL_REVIEW, PaymentActor.SYSTEM,
                CUTOFF.minus(Duration.ofDays(3)));
        saveHistory(reviewed.getId(), PaymentStatus.MANUAL_REVIEW, PaymentStatus.APPROVED, PaymentActor.MASTER,
                CUTOFF.minus(Duration.ofDays(2)));
        // 부분 환불 뒤 APPROVED로 돌아온 이력이 있어도 승인 시각은 처음 승인 이력
        saveHistory(old.getId(), PaymentStatus.REFUND_PENDING, PaymentStatus.APPROVED, PaymentActor.SYSTEM,
                CUTOFF.plus(Duration.ofDays(1)));

        Payment settled = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        settlementItemRepository.saveAll(List.of(
                SettlementItem.of(settled.getId(), saveSettlement("key-" + UUID.randomUUID()).getId(), 10_000L)));
        Payment requested = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        changeStatus(requested, PaymentStatus.REFUND_REQUESTED);
        approvedPayment(UUID.randomUUID(), CUTOFF.minus(Duration.ofDays(1)));

        // 정산 대상과 같은 결제 생성 순 (승인 시각 순이 아님)
        setPaymentCreatedAt(recent, "2026-11-01T00:00:00Z");
        setPaymentCreatedAt(reviewed, "2026-11-01T00:00:01Z");
        setPaymentCreatedAt(old, "2026-11-01T00:00:02Z");

        List<SettlementCandidate> candidates = paymentRepository.findSettlementCandidates(merchantId);

        assertEquals(List.of(
                new SettlementCandidate(recent.getId(), 10_000L, CUTOFF.plus(Duration.ofHours(1))),
                new SettlementCandidate(reviewed.getId(), 10_000L, CUTOFF.minus(Duration.ofDays(2))),
                new SettlementCandidate(old.getId(), 10_000L, CUTOFF.minus(Duration.ofDays(1)))),
                candidates);
    }

    @Test
    void settlementTargetsAreLimitedInPaymentCreationOrder() {
        Payment first = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        Payment second = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        Payment third = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        setPaymentCreatedAt(third, "2026-11-01T00:00:00Z");
        setPaymentCreatedAt(first, "2026-11-01T00:00:01Z");
        setPaymentCreatedAt(second, "2026-11-01T00:00:02Z");

        List<UUID> limited = paymentRepository.findSettlementTargets(merchantId, CUTOFF, 2).stream()
                .map(Payment::getId).toList();

        assertEquals(List.of(third.getId(), first.getId()), limited);
    }

    // 정산 항목

    @Test
    void paymentCanBeSettledOnlyOnce() {
        Payment payment = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        Settlement first = saveSettlement("key-" + UUID.randomUUID());
        Settlement second = saveSettlement("key-" + UUID.randomUUID());
        settlementItemRepository.saveAll(List.of(SettlementItem.of(payment.getId(), first.getId(), 10_000L)));

        assertThrows(DataIntegrityViolationException.class, () -> settlementItemRepository.saveAll(
                List.of(SettlementItem.of(payment.getId(), second.getId(), 10_000L))));
    }

    @Test
    void deletingItemsMakesPaymentsTargetsAgain() {
        Payment payment = approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1)));
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        settlementItemRepository.saveAll(List.of(SettlementItem.of(payment.getId(), settlement.getId(), 10_000L)));

        assertTrue(settlementItemRepository.existsByPaymentId(payment.getId()));
        assertEquals(1, settlementItemRepository.findAllBySettlementId(settlement.getId()).size());

        settlementItemRepository.deleteAllBySettlementId(settlement.getId());

        assertFalse(settlementItemRepository.existsByPaymentId(payment.getId()));
        assertEquals(List.of(payment.getId()), targetIds(CUTOFF));
    }

    @Test
    void countsItemsPerSettlementInOneQuery() {
        Settlement two = saveSettlement("key-" + UUID.randomUUID());
        Settlement none = saveSettlement("key-" + UUID.randomUUID());
        settlementItemRepository.saveAll(List.of(
                SettlementItem.of(approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1))).getId(), two.getId(), 1_000L),
                SettlementItem.of(approvedPayment(merchantId, CUTOFF.minus(Duration.ofDays(1))).getId(), two.getId(), 2_000L)));

        Map<UUID, Long> counts = settlementItemRepository.countBySettlementIds(List.of(two.getId(), none.getId()));

        assertEquals(Map.of(two.getId(), 2L), counts);
        assertTrue(settlementItemRepository.countBySettlementIds(List.of()).isEmpty());
    }

    // 정산

    @Test
    void sameRequesterCannotReuseIdempotencyKey() {
        Settlement saved = saveSettlement("same-key");

        assertEquals(saved.getId(), settlementRepository
                .findByRequestedByAndIdempotencyKey(saved.getRequestedBy(), "same-key").orElseThrow().getId());
        // 서비스에서는 트랜잭션 커밋 때 DataIntegrityViolationException으로 바뀝니다. 여기서는 직접 flush해 제약 이름으로 확인
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            settlementRepository.save(Settlement.request(merchantId, saved.getRequestedBy(), "same-key",
                    saved.getOwnerUserId(), saved.getOwnerWalletId(), CurrencyCode.KRW, 1_000L, CUTOFF));
            entityManager.flush();
        });
        assertTrue(exception.getMessage().contains("uk_settlements_requested_by_idempotency_key"));
    }

    @Test
    void paidSettlementIsStoredWithLedgerTransaction() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        UUID ledgerTransactionId = UUID.randomUUID();

        settlement.paid(ledgerTransactionId);
        settlementRepository.save(settlement);
        entityManager.flush();

        Map<String, Object> row = settlementRow(settlement.getId());
        assertEquals("PAID", row.get("status"));
        assertEquals(ledgerTransactionId, row.get("ledger_transaction_id"));
        assertNotNull(row.get("paid_at"));
    }

    // 한 트랜잭션에서 제약 위반이 나면 이후 문장이 실행되지 않아 경우마다 테스트를 나눔
    @Test
    void paidWithoutPaidAtIsRejected() {
        Settlement requested = saveSettlement("key-" + UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update settlements set status = 'PAID', ledger_transaction_id = ? where id = ?",
                UUID.randomUUID(), requested.getId()));
    }

    @Test
    void paidAtWithoutPaidIsRejected() {
        Settlement requested = saveSettlement("key-" + UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update settlements set paid_at = now() where id = ?", requested.getId()));
    }

    @Test
    void merchantSettlementsAreListedNewestFirst() {
        Settlement older = saveSettlement("key-" + UUID.randomUUID());
        Settlement newer = saveSettlement("key-" + UUID.randomUUID());
        setTimestamps(older.getId(), "2026-11-01T00:00:00Z", 0);
        setTimestamps(newer.getId(), "2026-11-02T00:00:00Z", 0);
        entityManager.clear();

        List<UUID> ids = settlementRepository.findPageByMerchantId(merchantId, null,
                        PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(Settlement::getId).getContent();

        assertEquals(List.of(newer.getId(), older.getId()), ids);
    }

    @Test
    void merchantSettlementsCanBeFilteredByStatus() {
        Settlement paid = saveSettlement("key-" + UUID.randomUUID());
        Settlement requested = saveSettlement("key-" + UUID.randomUUID());
        paid.paid(UUID.randomUUID());
        settlementRepository.save(paid);
        entityManager.flush();
        entityManager.clear();

        List<UUID> paidIds = settlementRepository.findPageByMerchantId(merchantId, SettlementStatus.PAID,
                        PageRequest.of(0, 20))
                .map(Settlement::getId).getContent();
        List<UUID> requestedIds = settlementRepository.findPageByMerchantId(merchantId, SettlementStatus.REQUESTED,
                        PageRequest.of(0, 20))
                .map(Settlement::getId).getContent();

        assertEquals(List.of(paid.getId()), paidIds);
        assertEquals(List.of(requested.getId()), requestedIds);
    }

    @Test
    void ownerSettlementsSpanMerchantsNewestFirstAndCanBeFilteredByStatus() {
        UUID ownerUserId = UUID.randomUUID();
        UUID otherMerchantId = merchantRepository.save(
                Merchant.create("정산 가맹점 2", CurrencyCode.KRW, ownerUserId)).getId();
        Settlement older = saveSettlement(merchantId, ownerUserId, "key-" + UUID.randomUUID());
        Settlement newer = saveSettlement(otherMerchantId, ownerUserId, "key-" + UUID.randomUUID());
        saveSettlement(merchantId, UUID.randomUUID(), "key-" + UUID.randomUUID());   // 다른 주인 → 빠짐
        newer.paid(UUID.randomUUID());
        settlementRepository.save(newer);
        entityManager.flush();
        setTimestamps(older.getId(), "2026-11-01T00:00:00Z", 0);
        setTimestamps(newer.getId(), "2026-11-02T00:00:00Z", 0);
        entityManager.clear();
        PageRequest newestFirst = PageRequest.of(0, 20,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        List<UUID> all = settlementRepository.findPageByOwnerUserId(ownerUserId, null, newestFirst)
                .map(Settlement::getId).getContent();
        List<UUID> paidOnly = settlementRepository.findPageByOwnerUserId(ownerUserId, SettlementStatus.PAID,
                        newestFirst)
                .map(Settlement::getId).getContent();

        assertEquals(List.of(newer.getId(), older.getId()), all);
        assertEquals(List.of(newer.getId()), paidOnly);
    }

    // 복구

    @Test
    void recoveryTargetsFollowBackoffAndSkipFinishedSettlements() {
        Instant now = Instant.parse("2026-11-07T00:10:00Z");
        Settlement firstRetry = saveSettlement("key-" + UUID.randomUUID());   // 0회, 1분 전 → 대상
        Settlement waiting = saveSettlement("key-" + UUID.randomUUID());      // 3회(2분 대기), 1분 전 → 아님
        Settlement backedOff = saveSettlement("key-" + UUID.randomUUID());    // 3회, 3분 전 → 대상
        Settlement paid = saveSettlement("key-" + UUID.randomUUID());
        setTimestamps(firstRetry.getId(), "2026-11-07T00:09:00Z", 0);
        setTimestamps(waiting.getId(), "2026-11-07T00:09:00Z", 3);
        setTimestamps(backedOff.getId(), "2026-11-07T00:07:00Z", 3);
        setTimestamps(paid.getId(), "2026-11-07T00:00:00Z", 0);
        jdbcTemplate.update("update settlements set status = 'PAID', ledger_transaction_id = ?, paid_at = now() "
                + "where id = ?", UUID.randomUUID(), paid.getId());

        List<UUID> ids = settlementRepository.findRecoveryTargets(now, 100).stream()
                .map(Settlement::getId).toList();

        assertTrue(ids.contains(firstRetry.getId()));
        assertTrue(ids.contains(backedOff.getId()));
        assertFalse(ids.contains(waiting.getId()));
        assertFalse(ids.contains(paid.getId()));
    }

    @Test
    void claimAndUnknownAttemptApplyOnlyOnce() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        setTimestamps(settlement.getId(), "2026-11-07T00:00:00Z", 0);
        Instant seen = Instant.parse("2026-11-07T00:00:00Z");
        Instant now = Instant.parse("2026-11-07T00:05:00Z");

        assertEquals(1, settlementRepository.claimForRecovery(settlement.getId(), seen, now));
        assertEquals(0, settlementRepository.claimForRecovery(settlement.getId(), seen, now));

        assertEquals(1, settlementRepository.recordUnknownAttempt(settlement.getId(), 0,
                SettlementStatus.REQUESTED, null, now));
        assertEquals(0, settlementRepository.recordUnknownAttempt(settlement.getId(), 0,
                SettlementStatus.REQUESTED, null, now));

        Map<String, Object> row = settlementRow(settlement.getId());
        assertEquals("REQUESTED", row.get("status"));
        assertEquals(1, row.get("attempt_count"));
    }

    // MANUAL_REVIEW

    @Test
    void fifthUnknownAttemptMovesToManualReviewAndLeavesRecoveryTargets() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        setTimestamps(settlement.getId(), "2026-11-07T00:00:00Z", 4);
        Instant now = Instant.parse("2026-11-07T01:00:00Z");

        assertEquals(1, settlementRepository.recordUnknownAttempt(settlement.getId(), 4,
                SettlementStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED, now));

        Map<String, Object> row = settlementRow(settlement.getId());
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("RETRY_EXHAUSTED", row.get("review_reason"));
        assertEquals(5, row.get("attempt_count"));
        assertFalse(settlementRepository.findRecoveryTargets(now.plus(Duration.ofDays(1)), 1000).stream()
                .anyMatch(target -> target.getId().equals(settlement.getId())));
    }

    @Test
    void markManualReviewOnlyChangesRequestedSettlement() {
        Settlement requested = saveSettlement("key-" + UUID.randomUUID());
        Settlement paid = saveSettlement("key-" + UUID.randomUUID());
        jdbcTemplate.update("update settlements set status = 'PAID', ledger_transaction_id = ?, paid_at = now() "
                + "where id = ?", UUID.randomUUID(), paid.getId());
        Instant now = Instant.parse("2026-11-07T00:00:00Z");

        assertEquals(1, settlementRepository.markManualReview(requested.getId(),
                SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, now));
        assertEquals(0, settlementRepository.markManualReview(paid.getId(),
                SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, now));

        assertEquals("LEDGER_REQUEST_NOT_ACCEPTED", settlementRow(requested.getId()).get("review_reason"));
        assertEquals("PAID", settlementRow(paid.getId()).get("status"));
    }

    @Test
    void manualReviewsAreListedOldestFirstAndFilteredByReason() {
        Settlement exhausted = saveSettlement("key-" + UUID.randomUUID());
        Settlement notAccepted = saveSettlement("key-" + UUID.randomUUID());
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        settlementRepository.markManualReview(exhausted.getId(), SettlementReviewReason.RETRY_EXHAUSTED, now);
        settlementRepository.markManualReview(notAccepted.getId(), SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED,
                now);
        jdbcTemplate.update("update settlements set created_at = cast(? as timestamptz) where id = ?",
                "2020-01-01T00:00:00Z", exhausted.getId());
        Sort oldestFirst = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

        List<UUID> all = settlementRepository.findManualReviews(null, PageRequest.of(0, 1000, oldestFirst))
                .map(Settlement::getId).getContent();
        List<UUID> notAcceptedOnly = settlementRepository.findManualReviews(
                        SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, PageRequest.of(0, 1000, oldestFirst))
                .map(Settlement::getId).getContent();

        assertEquals(exhausted.getId(), all.get(0));
        assertTrue(all.contains(notAccepted.getId()));
        assertTrue(notAcceptedOnly.contains(notAccepted.getId()));
        assertFalse(notAcceptedOnly.contains(exhausted.getId()));
        assertTrue(settlementRepository.countManualReviews() >= 2);
    }

    // 제약 위반 뒤에는 같은 트랜잭션에서 다른 SQL을 실행할 수 없어 경우별로 나눔
    @Test
    void manualReviewRequiresReason() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update settlements set status = 'MANUAL_REVIEW', review_reason = null where id = ?",
                settlement.getId()));
    }

    @Test
    void otherStatusesMustNotHaveReviewReason() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update settlements set review_reason = 'RETRY_EXHAUSTED' where id = ?", settlement.getId()));
    }

    @Test
    void resolutionsAreSearchedByActorAndSettlementNewestFirst() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        resolutionRepository.save(SettlementReviewResolution.of(settlement.getId(),
                SettlementReviewReason.RETRY_EXHAUSTED, ReviewLedgerStatus.POSTED, UUID.randomUUID(),
                SettlementStatus.PAID, actor, "재시도 한도 초과, 원장 반영 확인 → 정산 지급", "메모"));
        resolutionRepository.save(SettlementReviewResolution.of(settlement.getId(),
                SettlementReviewReason.RETRY_EXHAUSTED, ReviewLedgerStatus.NOT_FOUND, null,
                SettlementStatus.FAILED, UUID.randomUUID(), "재시도 한도 초과, 원장 기록 없음 → 정산 시간 초과 취소", null));
        entityManager.flush();

        var byActor = resolutionRepository.search(actor, null, PageRequest.of(0, 10));
        var bySettlement = resolutionRepository.search(null, settlement.getId(), PageRequest.of(0, 10));

        assertEquals(1, byActor.getTotalElements());
        assertEquals(SettlementStatus.PAID, byActor.getContent().get(0).getToStatus());
        assertEquals(2, bySettlement.getTotalElements());
    }

    @Test
    void resolutionLedgerTransactionIdMustMatchLedgerStatus() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "insert into settlement_review_resolutions (id, settlement_id, review_reason, ledger_status, "
                        + "ledger_transaction_id, to_status, actor_id, description, created_at) "
                        + "values (?, ?, 'RETRY_EXHAUSTED', 'POSTED', null, 'PAID', ?, '설명', now())",
                UUID.randomUUID(), settlement.getId(), UUID.randomUUID()));
    }

    // 자동 처리 기록은 처리한 회원 없이 SYSTEM으로 남음
    @Test
    void systemResolutionIsStoredWithoutActor() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        SettlementReviewResolution resolution = SettlementReviewResolution.ofSystem(
                settlement.getId(), SettlementReviewReason.RETRY_EXHAUSTED, ReviewLedgerStatus.POSTED,
                UUID.randomUUID(), SettlementStatus.PAID, "자동 처리: 재시도 한도 초과, 원장 반영 확인 → 정산 지급");
        resolutionRepository.save(resolution);
        entityManager.flush();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select actor_type, actor_id, memo from settlement_review_resolutions where id = ?", resolution.getId());
        assertEquals("SYSTEM", row.get("actor_type"));
        assertNull(row.get("actor_id"));
        assertNull(row.get("memo"));
    }

    @Test
    void operatorResolutionRequiresActor() {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "insert into settlement_review_resolutions (id, settlement_id, review_reason, ledger_status, "
                        + "ledger_transaction_id, to_status, actor_type, actor_id, description, created_at) "
                        + "values (?, ?, 'RETRY_EXHAUSTED', 'NOT_FOUND', null, 'FAILED', 'OPERATOR', null, '설명', now())",
                UUID.randomUUID(), settlement.getId()));
    }

    // 자동 처리 대상: MANUAL_REVIEW로 바뀐 지 기준 시각이 지난 정산만, (생성 시각, ID) 커서로 이어서
    @Test
    void manualReviewsAfterCursorSkipsRecentAndContinuesFromCursor() {
        Instant now = Instant.now();
        Settlement first = manualReviewSettlementAt("2001-01-01T00:00:01Z", "2001-01-01T00:00:01Z");
        Settlement recent = manualReviewSettlementAt("2001-01-01T00:00:01.5Z", now.toString());
        Settlement second = manualReviewSettlementAt("2001-01-01T00:00:02Z", "2001-01-01T00:00:02Z");
        Instant updatedBefore = now.minusSeconds(60);

        List<Settlement> page1 = settlementRepository.findManualReviewsAfter(updatedBefore, Instant.EPOCH,
                new UUID(0L, 0L), 1);
        assertEquals(List.of(first.getId()), page1.stream().map(Settlement::getId).toList());

        List<Settlement> page2 = settlementRepository.findManualReviewsAfter(updatedBefore,
                page1.get(0).getCreatedAt(), page1.get(0).getId(), 1);
        assertEquals(List.of(second.getId()), page2.stream().map(Settlement::getId).toList());
        assertFalse(page2.stream().map(Settlement::getId).toList().contains(recent.getId()));
    }

    @Test
    void requestedSettlementsCreatedBeforeAreCounted() {
        Settlement old = saveSettlement("key-" + UUID.randomUUID());
        Settlement recent = saveSettlement("key-" + UUID.randomUUID());
        jdbcTemplate.update("update settlements set created_at = cast(? as timestamptz) where id = ?",
                "2020-01-01T00:00:00Z", old.getId());
        Instant before = Instant.parse("2021-01-01T00:00:00Z");

        List<UUID> ids = settlementRepository.findRequestedIdsCreatedBefore(before, 1000);

        assertTrue(ids.contains(old.getId()));
        assertFalse(ids.contains(recent.getId()));
        assertTrue(settlementRepository.countRequestedCreatedBefore(before) >= 1);
    }

    private List<UUID> targetIds(Instant approvedBefore) {
        return paymentRepository.findSettlementTargets(merchantId, approvedBefore, 1_000).stream()
                .map(Payment::getId).toList();
    }

    // PENDING → APPROVED 이력을 지정한 시각으로 남긴 승인 결제
    private Payment approvedPayment(UUID paymentMerchantId, Instant approvedAt) {
        Payment payment = savePayment(paymentMerchantId, PaymentStatus.APPROVED);
        saveHistory(payment.getId(), PaymentStatus.PENDING, PaymentStatus.APPROVED, PaymentActor.SYSTEM, approvedAt);
        return payment;
    }

    private Payment savePayment(UUID paymentMerchantId, PaymentStatus status) {
        Payment payment = paymentRepository.save(Payment.create(UUID.randomUUID(), UUID.randomUUID(),
                "settlement-it-" + UUID.randomUUID(), paymentMerchantId, "정산 가맹점", "테스트 주문",
                CurrencyCode.KRW, 10_000L));
        entityManager.flush();
        jdbcTemplate.update("update payments set status = ? where id = ?", status.name(), payment.getId());
        return payment;
    }

    private void setPaymentCreatedAt(Payment payment, String at) {
        jdbcTemplate.update("update payments set created_at = cast(? as timestamptz) where id = ?", at, payment.getId());
    }

    private void changeStatus(Payment payment, PaymentStatus status) {
        jdbcTemplate.update("update payments set status = ? where id = ?", status.name(), payment.getId());
    }

    private void saveHistory(UUID paymentId, PaymentStatus previous, PaymentStatus current, PaymentActor actor,
                             Instant createdAt) {
        PaymentHistory history = PaymentHistory.of(paymentId, previous, current, null, actor);
        paymentHistoryRepository.save(history);
        entityManager.flush();
        jdbcTemplate.update("update payment_histories set created_at = ? where id = ?",
                Timestamp.from(createdAt), history.getId());
    }

    private Settlement saveSettlement(String idempotencyKey) {
        return saveSettlement(merchantId, UUID.randomUUID(), idempotencyKey);
    }

    private Settlement saveSettlement(UUID merchantId, UUID ownerUserId, String idempotencyKey) {
        Settlement settlement = settlementRepository.save(Settlement.request(merchantId, UUID.randomUUID(),
                idempotencyKey, ownerUserId, UUID.randomUUID(), CurrencyCode.KRW, 10_000L, CUTOFF));
        entityManager.flush();
        return settlement;
    }

    private Settlement manualReviewSettlementAt(String createdAt, String updatedAt) {
        Settlement settlement = saveSettlement("key-" + UUID.randomUUID());
        jdbcTemplate.update("update settlements set status = 'MANUAL_REVIEW', review_reason = 'RETRY_EXHAUSTED', "
                        + "created_at = cast(? as timestamptz), updated_at = cast(? as timestamptz) where id = ?",
                createdAt, updatedAt, settlement.getId());
        entityManager.clear();
        return settlementRepository.findById(settlement.getId()).orElseThrow();
    }

    private void setTimestamps(UUID settlementId, String at, int attemptCount) {
        jdbcTemplate.update("update settlements set created_at = cast(? as timestamptz), "
                        + "updated_at = cast(? as timestamptz), attempt_count = ? where id = ?",
                at, at, attemptCount, settlementId);
    }

    private Map<String, Object> settlementRow(UUID settlementId) {
        return jdbcTemplate.queryForMap("select * from settlements where id = ?", settlementId);
    }
}

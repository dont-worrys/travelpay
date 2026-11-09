package com.travelpay.payment.domain.merchant;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.SettlementCandidate;
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

        List<SettlementCandidate> candidates = paymentRepository.findSettlementCandidates(merchantId);

        assertEquals(List.of(
                new SettlementCandidate(reviewed.getId(), 10_000L, CUTOFF.minus(Duration.ofDays(2))),
                new SettlementCandidate(old.getId(), 10_000L, CUTOFF.minus(Duration.ofDays(1))),
                new SettlementCandidate(recent.getId(), 10_000L, CUTOFF.plus(Duration.ofHours(1)))),
                candidates);
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
    }

    @Test
    void merchantSettlementsAreListedNewestFirst() {
        Settlement older = saveSettlement("key-" + UUID.randomUUID());
        Settlement newer = saveSettlement("key-" + UUID.randomUUID());
        setTimestamps(older.getId(), "2026-11-01T00:00:00Z", 0);
        setTimestamps(newer.getId(), "2026-11-02T00:00:00Z", 0);
        entityManager.clear();

        List<UUID> ids = settlementRepository.findPageByMerchantId(merchantId,
                        PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(Settlement::getId).getContent();

        assertEquals(List.of(newer.getId(), older.getId()), ids);
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
        jdbcTemplate.update("update settlements set status = 'PAID', ledger_transaction_id = ? where id = ?",
                UUID.randomUUID(), paid.getId());

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

        assertEquals(1, settlementRepository.recordUnknownAttempt(settlement.getId(), 0, now));
        assertEquals(0, settlementRepository.recordUnknownAttempt(settlement.getId(), 0, now));

        Map<String, Object> row = settlementRow(settlement.getId());
        assertEquals("REQUESTED", row.get("status"));
        assertEquals(1, row.get("attempt_count"));
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
        return paymentRepository.findSettlementTargets(merchantId, approvedBefore).stream()
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
        Settlement settlement = settlementRepository.save(Settlement.request(merchantId, UUID.randomUUID(),
                idempotencyKey, UUID.randomUUID(), UUID.randomUUID(), CurrencyCode.KRW, 10_000L, CUTOFF));
        entityManager.flush();
        return settlement;
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

package com.travelpay.payment.domain.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.payment.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL_REVIEW 처리용 쿼리·조건부 업데이트·처리 기록 제약을 실제 PostgreSQL(Flyway 적용)에서 검증합니다.
 * 컨텍스트가 뜨면 ddl-auto=validate로 PaymentReviewResolution 매핑도 함께 확인됩니다.
 * Testcontainers PostgreSQL을 사용하며 Docker가 없으면 건너뜁니다.
 * 각 테스트는 트랜잭션 안에서 실행 후 롤백되며, 다른 테스트의 데이터가 있어도 영향을 받지 않도록 만든 행의 ID로만 확인합니다.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "transfer.recovery.enabled=false",
        "payment.settlement-recovery.enabled=false"})
@Transactional
class PaymentReviewRepositoryIntegrationTest extends PostgresContainerSupport {

    private static final PageRequest ALL = PageRequest.of(0, 1000);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PaymentReviewResolutionRepository resolutionRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void refundManualReviewsJoinPaymentForCustomerAndMerchant() {
        Payment payment = savePayment(PaymentStatus.REFUND_PENDING, null);
        Refund refund = saveRefund(payment.getId(), RefundStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED);

        RefundManualReview row = refundRepository.findManualReviews(null, ALL).getContent().stream()
                .filter(review -> review.refundId().equals(refund.getId()))
                .findFirst().orElseThrow();

        assertEquals(payment.getId(), row.paymentId());
        assertEquals(payment.getUserId(), row.customerUserId());
        assertEquals(payment.getMerchantId(), row.merchantId());
        assertEquals(refund.getUserId(), row.requesterUserId());
        assertEquals(refund.getAmount(), row.amount());
        assertEquals(CurrencyCode.KRW, row.currency());
        assertEquals(SettlementReviewReason.RETRY_EXHAUSTED, row.reviewReason());
        assertEquals(5, row.attemptCount());
        assertNotNull(row.createdAt());
    }

    @Test
    void refundManualReviewsFilterByReasonAndCountMatches() {
        Refund exhausted = saveRefund(savePayment(PaymentStatus.REFUND_PENDING, null).getId(),
                RefundStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED);
        Refund notAccepted = saveRefund(savePayment(PaymentStatus.REFUND_PENDING, null).getId(),
                RefundStatus.MANUAL_REVIEW, SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        Refund requested = saveRefund(savePayment(PaymentStatus.REFUND_PENDING, null).getId(),
                RefundStatus.REQUESTED, null);

        Page<RefundManualReview> page = refundRepository.findManualReviews(SettlementReviewReason.RETRY_EXHAUSTED, ALL);
        List<UUID> ids = page.getContent().stream().map(RefundManualReview::refundId).toList();

        assertTrue(ids.contains(exhausted.getId()));
        assertFalse(ids.contains(notAccepted.getId()));
        assertFalse(ids.contains(requested.getId()));
        assertEquals(countRows("refunds", "RETRY_EXHAUSTED"), page.getTotalElements());

        List<UUID> all = refundRepository.findManualReviews(null, ALL).getContent().stream()
                .map(RefundManualReview::refundId).toList();
        assertTrue(all.containsAll(List.of(exhausted.getId(), notAccepted.getId())));
        assertFalse(all.contains(requested.getId()));
    }

    @Test
    void paymentManualReviewsFilterByReasonAndCountMatches() {
        Payment exhausted = savePayment(PaymentStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED);
        Payment notAccepted = savePayment(PaymentStatus.MANUAL_REVIEW, SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        Payment pending = savePayment(PaymentStatus.PENDING, null);

        Page<Payment> page = paymentRepository.findManualReviews(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED,
                PageRequest.of(0, 1000, Sort.by("createdAt", "id")));
        List<UUID> ids = page.getContent().stream().map(Payment::getId).toList();

        assertTrue(ids.contains(notAccepted.getId()));
        assertFalse(ids.contains(exhausted.getId()));
        assertFalse(ids.contains(pending.getId()));
        assertEquals(countRows("payments", "LEDGER_REQUEST_NOT_ACCEPTED"), page.getTotalElements());
    }

    @Test
    void resolveCompletedChangesOnlyManualReviewRefund() {
        UUID ledgerId = UUID.randomUUID();
        Refund refund = saveRefund(savePayment(PaymentStatus.REFUND_PENDING, null).getId(),
                RefundStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED);

        assertEquals(1, refundRepository.resolveCompleted(refund.getId(), ledgerId, Instant.now()));
        assertEquals(0, refundRepository.resolveCompleted(refund.getId(), ledgerId, Instant.now()));

        Map<String, Object> row = refundRow(refund.getId());
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(ledgerId, row.get("ledger_id"));
        assertNull(row.get("review_reason"));
    }

    @Test
    void resolveDeclinedChangesOnlyManualReviewRefund() {
        Refund manualReview = saveRefund(savePayment(PaymentStatus.REFUND_PENDING, null).getId(),
                RefundStatus.MANUAL_REVIEW, SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        Refund requested = saveRefund(savePayment(PaymentStatus.REFUND_PENDING, null).getId(),
                RefundStatus.REQUESTED, null);

        assertEquals(1, refundRepository.resolveDeclined(manualReview.getId(), "환불 처리 중 오류로 취소되었습니다.", Instant.now()));
        assertEquals(0, refundRepository.resolveDeclined(requested.getId(), "x", Instant.now()));

        Map<String, Object> row = refundRow(manualReview.getId());
        assertEquals("DECLINED", row.get("status"));
        assertEquals("환불 처리 중 오류로 취소되었습니다.", row.get("decline_reason"));
        assertNull(row.get("review_reason"));
        assertEquals("REQUESTED", refundRow(requested.getId()).get("status"));
    }

    @Test
    void resolveRetryReturnsRefundToRequestedWithZeroAttempts() {
        Refund refund = saveRefund(savePayment(PaymentStatus.REFUND_PENDING, null).getId(),
                RefundStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED);

        assertEquals(1, refundRepository.resolveRetry(refund.getId(), Instant.now()));
        assertEquals(0, refundRepository.resolveRetry(refund.getId(), Instant.now()));

        Map<String, Object> row = refundRow(refund.getId());
        assertEquals("REQUESTED", row.get("status"));
        assertEquals(0, row.get("attempt_count"));
        assertNull(row.get("review_reason"));
    }

    @Test
    void savesResolutionRecordsForPaymentAndRefund() {
        Payment payment = savePayment(PaymentStatus.REFUND_PENDING, null);
        Refund refund = saveRefund(payment.getId(), RefundStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED);
        UUID actorId = UUID.randomUUID();

        resolutionRepository.save(PaymentReviewResolution.ofPayment(payment.getId(),
                SettlementReviewReason.RETRY_EXHAUSTED, ReviewLedgerStatus.NOT_FOUND, null,
                PaymentStatus.DECLINED, actorId, "재시도 한도 초과, 원장 기록 없음 → 결제 시간 초과 취소", null));
        resolutionRepository.save(PaymentReviewResolution.ofRefund(payment.getId(), refund.getId(),
                SettlementReviewReason.RETRY_EXHAUSTED, ReviewLedgerStatus.POSTED, UUID.randomUUID(),
                RefundStatus.COMPLETED, actorId, "재시도 한도 초과, 원장 반영 확인 → 환불 완료", "메모"));
        entityManager.flush();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select refund_id, ledger_status, to_status, actor_id, description, memo from payment_review_resolutions"
                        + " where payment_id = ? order by created_at, refund_id nulls first", payment.getId());
        assertEquals(2, rows.size());
        assertNull(rows.get(0).get("refund_id"));
        assertEquals("NOT_FOUND", rows.get(0).get("ledger_status"));
        assertEquals("DECLINED", rows.get(0).get("to_status"));
        assertEquals("재시도 한도 초과, 원장 기록 없음 → 결제 시간 초과 취소", rows.get(0).get("description"));
        assertNull(rows.get(0).get("memo"));
        assertEquals(refund.getId(), rows.get(1).get("refund_id"));
        assertEquals("COMPLETED", rows.get(1).get("to_status"));
        assertEquals(actorId, rows.get(1).get("actor_id"));
        assertEquals("메모", rows.get(1).get("memo"));
    }

    @Test
    void resolutionRequiresLedgerTransactionWhenLedgerHasRecord() {
        Payment payment = savePayment(PaymentStatus.APPROVED, null);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                insert into payment_review_resolutions
                    (id, payment_id, review_reason, ledger_status, ledger_transaction_id, to_status, actor_id,
                     description, created_at)
                values (?, ?, 'RETRY_EXHAUSTED', 'POSTED', null, 'APPROVED', ?, '테스트', now())
                """, UUID.randomUUID(), payment.getId(), UUID.randomUUID()));
    }

    @Test
    void resolutionRejectsUnknownPayment() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                insert into payment_review_resolutions
                    (id, payment_id, review_reason, ledger_status, ledger_transaction_id, to_status, actor_id,
                     description, created_at)
                values (?, ?, 'RETRY_EXHAUSTED', 'NOT_FOUND', null, 'DECLINED', ?, '테스트', now())
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    }

    // 운영자 처리로 승인된 결제도 환불 기간 기준이 되는 승인 시각을 가져야 환불할 수 있음
    @Test
    void approvedAtIncludesApprovalByManualReviewResolution() {
        Payment payment = savePayment(PaymentStatus.APPROVED, null);
        saveHistory(payment.getId(), PaymentStatus.PENDING, PaymentStatus.MANUAL_REVIEW, PaymentActor.SYSTEM);
        Instant approvedAt = saveHistory(payment.getId(), PaymentStatus.MANUAL_REVIEW, PaymentStatus.APPROVED,
                PaymentActor.MASTER);

        assertEquals(approvedAt, paymentHistoryRepository.findApprovedAt(payment.getId()).orElseThrow());
    }

    // 부분 환불 후 REFUND_PENDING → APPROVED로 돌아온 이력은 승인 시각이 아님
    @Test
    void approvedAtIgnoresReturnFromPartialRefund() {
        Payment payment = savePayment(PaymentStatus.APPROVED, null);
        Instant approvedAt = saveHistory(payment.getId(), PaymentStatus.PENDING, PaymentStatus.APPROVED,
                PaymentActor.SYSTEM);
        saveHistory(payment.getId(), PaymentStatus.APPROVED, PaymentStatus.REFUND_PENDING, PaymentActor.USER);
        saveHistory(payment.getId(), PaymentStatus.REFUND_PENDING, PaymentStatus.APPROVED, PaymentActor.SYSTEM);

        assertEquals(approvedAt, paymentHistoryRepository.findApprovedAt(payment.getId()).orElseThrow());
    }

    // 처리 기록 목록: type·actorId·paymentId 조건과 최신순 정렬
    @Test
    void searchesResolutionsByTargetActorAndPaymentNewestFirst() {
        UUID actor = UUID.randomUUID();
        UUID otherActor = UUID.randomUUID();
        Payment payment = savePayment(PaymentStatus.REFUND_PENDING, null);
        Refund refund = saveRefund(payment.getId(), RefundStatus.MANUAL_REVIEW, SettlementReviewReason.RETRY_EXHAUSTED);
        Payment otherPayment = savePayment(PaymentStatus.APPROVED, null);

        UUID paymentRecord = saveResolution(payment.getId(), null, actor, "2026-11-05T01:00:00Z");
        UUID refundRecord = saveResolution(payment.getId(), refund.getId(), actor, "2026-11-05T02:00:00Z");
        UUID otherActorRecord = saveResolution(otherPayment.getId(), null, otherActor, "2026-11-05T03:00:00Z");
        PageRequest newestFirst = PageRequest.of(0, 1000,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        assertEquals(List.of(refundRecord, paymentRecord),
                ids(resolutionRepository.search(null, actor, null, newestFirst)));
        assertEquals(List.of(paymentRecord),
                ids(resolutionRepository.search(ReviewTarget.PAYMENT, actor, null, newestFirst)));
        assertEquals(List.of(refundRecord),
                ids(resolutionRepository.search(ReviewTarget.REFUND, null, payment.getId(), newestFirst)));
        assertEquals(List.of(otherActorRecord),
                ids(resolutionRepository.search(null, null, otherPayment.getId(), newestFirst)));
        assertEquals(List.of(refundRecord, paymentRecord),
                ids(resolutionRepository.search(null, null, payment.getId(), newestFirst)));
    }

    @Test
    void paymentListIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = current_schema() and tablename = 'payments'",
                String.class);

        assertTrue(indexes.contains("ix_payments_merchant_id_created_at_id"), indexes::toString);
        assertTrue(indexes.contains("ix_payments_user_id_created_at_id"), indexes::toString);
    }

    // ── 준비 ──

    // 상태와 사유는 DB에서 직접 맞춤(MANUAL_REVIEW ⇔ review_reason 제약)
    private Payment savePayment(PaymentStatus status, SettlementReviewReason reviewReason) {
        Payment payment = paymentRepository.save(Payment.create(UUID.randomUUID(), UUID.randomUUID(),
                "review-it-" + UUID.randomUUID(), UUID.randomUUID(), "테스트 가맹점", "테스트 주문",
                CurrencyCode.KRW, 10_000L));
        entityManager.flush();
        jdbcTemplate.update("update payments set status = ?, review_reason = ?, attempt_count = 5 where id = ?",
                status.name(), reviewReason == null ? null : reviewReason.name(), payment.getId());
        entityManager.clear();
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private Refund saveRefund(UUID paymentId, RefundStatus status, SettlementReviewReason reviewReason) {
        Refund refund = refundRepository.save(Refund.request(paymentId, UUID.randomUUID(),
                "review-it-" + UUID.randomUUID(), 3_000L, CurrencyCode.KRW, "테스트 환불"));
        entityManager.flush();
        jdbcTemplate.update("update refunds set status = ?, review_reason = ?, attempt_count = 5 where id = ?",
                status.name(), reviewReason == null ? null : reviewReason.name(), refund.getId());
        entityManager.clear();
        return refundRepository.findById(refund.getId()).orElseThrow();
    }

    // 저장된 이력의 생성 시각(DB 값, 마이크로초)을 돌려줌
    private Instant saveHistory(UUID paymentId, PaymentStatus previous, PaymentStatus current, PaymentActor actor) {
        PaymentHistory history = PaymentHistory.of(paymentId, previous, current, null, actor);
        paymentHistoryRepository.save(history);
        entityManager.flush();
        return jdbcTemplate.queryForObject("select created_at from payment_histories where id = ?",
                Timestamp.class, history.getId()).toInstant();
    }

    // 처리 기록을 저장하고 정렬 확인을 위해 생성 시각을 지정한 값으로 맞춤
    private UUID saveResolution(UUID paymentId, UUID refundId, UUID actorId, String createdAt) {
        PaymentReviewResolution resolution = refundId == null
                ? PaymentReviewResolution.ofPayment(paymentId, SettlementReviewReason.RETRY_EXHAUSTED,
                        ReviewLedgerStatus.NOT_FOUND, null, PaymentStatus.DECLINED, actorId, "테스트 설명", null)
                : PaymentReviewResolution.ofRefund(paymentId, refundId, SettlementReviewReason.RETRY_EXHAUSTED,
                        ReviewLedgerStatus.NOT_FOUND, null, RefundStatus.REQUESTED, actorId, "테스트 설명", null);
        resolutionRepository.save(resolution);
        entityManager.flush();
        jdbcTemplate.update("update payment_review_resolutions set created_at = cast(? as timestamptz) where id = ?",
                createdAt, resolution.getId());
        entityManager.clear();
        return resolution.getId();
    }

    private static List<UUID> ids(Page<PaymentReviewResolution> page) {
        return page.getContent().stream().map(PaymentReviewResolution::getId).toList();
    }

    private Map<String, Object> refundRow(UUID refundId) {
        return jdbcTemplate.queryForMap(
                "select status, ledger_id, decline_reason, review_reason, attempt_count from refunds where id = ?",
                refundId);
    }

    private long countRows(String table, String reviewReason) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + table
                + " where status = 'MANUAL_REVIEW' and review_reason = ?", Long.class, reviewReason);
        return count == null ? 0 : count;
    }
}

package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.SettlementCandidate;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository  {

    private final PaymentJpaRepository paymentJpaRepository;

    @Override
    public Payment save(Payment payment) {
        return paymentJpaRepository.save(payment);
    }

    @Override
    public Optional<Payment> findById(UUID paymentId) {
        return paymentJpaRepository.findById(paymentId);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(UUID paymentId) {
        return paymentJpaRepository.findByIdForUpdate(paymentId);
    }

    @Override
    public Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey) {
        return paymentJpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    @Override
    public Page<Payment> findPageByUserId(UUID userId, PaymentStatus status, Pageable pageable) {
        if (status == null) {
            return paymentJpaRepository.findByUserId(userId, pageable);
        }
        return paymentJpaRepository.findByUserIdAndStatus(userId, status, pageable);
    }

    @Override
    public Page<Payment> findPageByMerchantId(UUID merchantId, PaymentStatus status, Pageable pageable) {
        if (status == null) {
            return paymentJpaRepository.findByMerchantId(merchantId, pageable);
        }
        return paymentJpaRepository.findByMerchantIdAndStatus(merchantId, status, pageable);
    }

    @Override
    public Page<Payment> findManualReviews(SettlementReviewReason reviewReason, Pageable pageable) {
        if (reviewReason == null) {
            return paymentJpaRepository.findByStatus(PaymentStatus.MANUAL_REVIEW, pageable);
        }
        return paymentJpaRepository.findByStatusAndReviewReason(PaymentStatus.MANUAL_REVIEW, reviewReason, pageable);
    }

    @Override
    public Page<Payment> findRefundRequestsUpdatedBefore(Instant before, Pageable pageable) {
        return paymentJpaRepository.findByStatusAndUpdatedAtBefore(PaymentStatus.REFUND_REQUESTED, before, pageable);
    }

    @Override
    public long countRefundRequestsUpdatedBefore(Instant before) {
        return paymentJpaRepository.countByStatusAndUpdatedAtBefore(PaymentStatus.REFUND_REQUESTED, before);
    }

    @Override
    public List<Payment> findSettlementTargets(UUID merchantId, Instant approvedBefore, int limit) {
        return paymentJpaRepository.findSettlementTargets(merchantId, approvedBefore, limit);
    }

    @Override
    public List<SettlementCandidate> findSettlementCandidates(UUID merchantId) {
        return paymentJpaRepository.findSettlementCandidates(merchantId);
    }

    @Override
    public List<Payment> findRecoveryTargets(Instant now, int limit) {
        return paymentJpaRepository.findRecoveryTargets(now, limit);
    }

    @Override
    public int claimForRecovery(UUID paymentId, Instant seenUpdatedAt, Instant now) {
        return paymentJpaRepository.claimForRecovery(paymentId, seenUpdatedAt, now);
    }

    @Override
    public int recordUnknownAttempt(UUID paymentId, int expectedAttempts, PaymentStatus status,
                                    SettlementReviewReason reviewReason, Instant now) {
        return paymentJpaRepository.recordUnknownAttempt(paymentId, expectedAttempts, status, reviewReason, now);
    }

    @Override
    public int markManualReview(UUID paymentId, SettlementReviewReason reviewReason, Instant now) {
        return paymentJpaRepository.markManualReview(paymentId, reviewReason, now);
    }

    @Override
    public long countByStatus(PaymentStatus status) {
        return paymentJpaRepository.countByStatus(status);
    }

    @Override
    public long countPendingCreatedBefore(Instant before) {
        return paymentJpaRepository.countByStatusAndCreatedAtBefore(PaymentStatus.PENDING, before);
    }

    @Override
    public List<UUID> findPendingIdsCreatedBefore(Instant before, int limit) {
        return paymentJpaRepository.findIdsByStatusAndCreatedAtBefore(
                PaymentStatus.PENDING, before, PageRequest.of(0, limit));
    }
}

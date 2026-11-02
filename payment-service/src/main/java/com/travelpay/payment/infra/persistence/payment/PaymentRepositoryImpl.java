package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    public List<Payment> findStalePending(Instant before, int limit) {
        return paymentJpaRepository.findByStatusAndUpdatedAtBefore(
                PaymentStatus.PENDING, before, PageRequest.of(0, limit, Sort.by("updatedAt")));
    }

    @Override
    public void deferPendingRetry(UUID paymentId, Instant retriedAt) {
        paymentJpaRepository.touchUpdatedAt(paymentId, PaymentStatus.PENDING, retriedAt);
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

package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundRepository;
import com.travelpay.payment.domain.payment.RefundStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RefundRepositoryImpl implements RefundRepository {

    private final RefundJpaRepository refundJpaRepository;

    @Override
    public Refund save(Refund refund) {
        return refundJpaRepository.save(refund);
    }

    @Override
    public Optional<Refund> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey) {
        return refundJpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    @Override
    public List<Refund> findAllByPaymentId(UUID paymentId) {
        return refundJpaRepository.findAllByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    @Override
    public List<Refund> findStaleRequested(Instant before, int limit) {
        return refundJpaRepository.findByStatusAndUpdatedAtBefore(
                RefundStatus.REQUESTED, before, PageRequest.of(0, limit, Sort.by("updatedAt")));
    }
}

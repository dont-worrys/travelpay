package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    List<Refund> findAllByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
    List<Refund> findByStatusAndUpdatedAtBefore(RefundStatus status, Instant before, Pageable pageable);
}

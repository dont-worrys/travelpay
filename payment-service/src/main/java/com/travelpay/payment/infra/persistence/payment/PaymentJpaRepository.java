package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    Page<Payment> findByUserId(UUID userId, Pageable pageable);
    Page<Payment> findByUserIdAndStatus(UUID userId, PaymentStatus status, Pageable pageable);
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant before, Pageable pageable);
}

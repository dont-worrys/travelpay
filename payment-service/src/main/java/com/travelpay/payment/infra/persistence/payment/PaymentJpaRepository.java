package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}

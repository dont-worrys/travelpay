package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentHistoryJpaRepository extends JpaRepository<PaymentHistory, UUID> {

    @Query("""
            select h.createdAt from PaymentHistory h
            where h.paymentId = :paymentId
              and h.previousStatus = :previous and h.currentStatus = :current
            """)
    Optional<Instant> findTransitionedAt(UUID paymentId, PaymentStatus previous, PaymentStatus current);
}

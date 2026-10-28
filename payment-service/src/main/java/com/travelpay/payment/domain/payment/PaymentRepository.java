package com.travelpay.payment.domain.payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    Payment save(Payment payment);
    Optional<Payment> findById(UUID paymentId);
    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}

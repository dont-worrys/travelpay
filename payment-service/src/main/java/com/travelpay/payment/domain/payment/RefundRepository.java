package com.travelpay.payment.domain.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    List<Refund> findAllByPaymentId(UUID paymentId);
    List<Refund> findStaleRequested(Instant before, int limit);
}

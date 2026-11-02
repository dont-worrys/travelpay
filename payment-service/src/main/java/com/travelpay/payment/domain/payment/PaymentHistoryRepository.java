package com.travelpay.payment.domain.payment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentHistoryRepository {
    void save(PaymentHistory paymentHistory);

    // 결제가 처음 승인된 시각 (PENDING → APPROVED 이력)
    Optional<Instant> findApprovedAt(UUID paymentId);
}

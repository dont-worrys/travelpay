package com.travelpay.payment.domain.payment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PaymentHistoryRepository {
    void save(PaymentHistory paymentHistory);

    // 결제가 처음 승인된 시각 (PENDING → APPROVED, 또는 운영자 처리로 MANUAL_REVIEW → APPROVED)
    Optional<Instant> findApprovedAt(UUID paymentId);

    // 결제 한 건의 상태 이력, 오래된 순
    List<PaymentHistory> findAllByPaymentId(UUID paymentId);

    // 결제별 가장 최근 고객 환불 신청 사유.
    Map<UUID, String> findRefundRequestReasons(Collection<UUID> paymentIds);
}

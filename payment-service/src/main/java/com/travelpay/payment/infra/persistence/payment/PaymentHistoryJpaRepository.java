package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PaymentHistoryJpaRepository extends JpaRepository<PaymentHistory, UUID> {

    // 부분 환불로 돌아온 REFUND_PENDING → APPROVED는 제외.
    // 승인 이력은 결제당 하나뿐(MANUAL_REVIEW는 PENDING에서만 들어가고, APPROVED는 다시 PENDING·MANUAL_REVIEW로 가지 않음)
    @Query("""
            select h.createdAt from PaymentHistory h
            where h.paymentId = :paymentId
              and h.previousStatus in :fromStatuses
              and h.currentStatus = com.travelpay.payment.domain.payment.PaymentStatus.APPROVED
            """)
    Optional<Instant> findApprovedAt(UUID paymentId, Collection<PaymentStatus> fromStatuses);
}

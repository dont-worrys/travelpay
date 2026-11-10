package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentHistoryRepositoryImpl implements PaymentHistoryRepository {

    private final PaymentHistoryJpaRepository paymentHistoryJpaRepository;

    @Override
    public void save(PaymentHistory paymentHistory) {
        paymentHistoryJpaRepository.save(paymentHistory);
    }

    @Override
    public Optional<Instant> findApprovedAt(UUID paymentId) {
        return paymentHistoryJpaRepository.findApprovedAt(
                paymentId, List.of(PaymentStatus.PENDING, PaymentStatus.MANUAL_REVIEW));
    }

    @Override
    public List<PaymentHistory> findAllByPaymentId(UUID paymentId) {
        return paymentHistoryJpaRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    @Override
    public Map<UUID, String> findRefundRequestReasons(Collection<UUID> paymentIds) {
        Map<UUID, String> reasons = new HashMap<>();
        if (paymentIds.isEmpty()) {
            return reasons;
        }
        // 최신순으로 읽어 결제마다 처음 나온(가장 최근) 신청 이력만 사용.
        for (PaymentHistory history : paymentHistoryJpaRepository.findChangedToStatusNewestFirst(
                paymentIds, PaymentStatus.REFUND_REQUESTED)) {
            if (!reasons.containsKey(history.getPaymentId())) {
                reasons.put(history.getPaymentId(), history.getReason());
            }
        }
        return reasons;
    }
}

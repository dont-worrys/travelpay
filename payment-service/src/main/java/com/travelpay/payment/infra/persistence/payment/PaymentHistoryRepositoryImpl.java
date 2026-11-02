package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
        return paymentHistoryJpaRepository.findTransitionedAt(
                paymentId, PaymentStatus.PENDING, PaymentStatus.APPROVED);
    }
}

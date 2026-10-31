package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentHistoryRepositoryImpl implements PaymentHistoryRepository {

    private final PaymentHistoryJpaRepository paymentHistoryJpaRepository;

    @Override
    public void save(PaymentHistory paymentHistory) {
        paymentHistoryJpaRepository.save(paymentHistory);
    }
}

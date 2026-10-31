package com.travelpay.payment.domain.payment;

public interface PaymentHistoryRepository {
    void save(PaymentHistory paymentHistory);
}

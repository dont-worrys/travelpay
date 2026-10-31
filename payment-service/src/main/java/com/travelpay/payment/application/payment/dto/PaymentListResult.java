package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentListResult(
        UUID paymentId,
        PaymentStatus status,
        Long amount,
        CurrencyCode currency,
        String merchantName,
        Instant createdAt
) {
    public static PaymentListResult from(Payment payment) {
        return new PaymentListResult(
                payment.getId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                payment.getMerchantName(), payment.getCreatedAt());
    }
}

package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResult(
        UUID paymentId,
        PaymentStatus status,
        Long amount,
        CurrencyCode currency,
        String merchantName,
        String paymentInfo,
        String declineReason,
        List<RefundResult> refunds,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResult from(Payment payment) {
        return from(payment, List.of());
    }

    public static PaymentResult from(Payment payment, List<RefundResult> refunds) {
        return new PaymentResult(
                payment.getId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                payment.getMerchantName(), payment.getPaymentInfo(), payment.getDeclineReason(),
                List.copyOf(refunds),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }
}

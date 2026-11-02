package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

// 가맹점 결제 목록 한 건. refundedAmount는 완료된 환불 합계
public record MerchantPaymentListResult(
        UUID paymentId,
        PaymentStatus status,
        Long amount,
        Long refundedAmount,
        CurrencyCode currency,
        String paymentInfo,
        Instant createdAt
) {
    public static MerchantPaymentListResult from(Payment payment, long refundedAmount) {
        return new MerchantPaymentListResult(
                payment.getId(), payment.getStatus(), payment.getAmount(), refundedAmount,
                payment.getCurrency(), payment.getPaymentInfo(), payment.getCreatedAt());
    }
}

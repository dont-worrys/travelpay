package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record PaymentResult(
        UUID paymentId,
        PaymentStatus status,
        Long amount,
        CurrencyCode currency,
        String merchantName,
        String paymentInfo,
        String declineReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static PaymentResult from(Payment payment) {
        return new PaymentResult(
                payment.getId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                payment.getMerchantName(), payment.getPaymentInfo(), payment.getDeclineReason(),
                toKst(payment.getCreatedAt()), toKst(payment.getUpdatedAt()));
    }

    private static OffsetDateTime toKst(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).withZoneSameInstant(KST).toOffsetDateTime();
    }
}

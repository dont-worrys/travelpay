package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record PaymentListResult(
        UUID paymentId,
        PaymentStatus status,
        Long amount,
        CurrencyCode currency,
        String merchantName,
        OffsetDateTime createdAt
) {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static PaymentListResult from(Payment payment) {
        return new PaymentListResult(
                payment.getId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                payment.getMerchantName(), toKst(payment.getCreatedAt()));
    }

    private static OffsetDateTime toKst(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).withZoneSameInstant(KST).toOffsetDateTime();
    }
}

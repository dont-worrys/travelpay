package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;

import java.time.Instant;
import java.util.UUID;

/**
 * 오래 처리되지 않은 환불 신청 목록 한 줄.
 * 신청 중에는 결제 행이 바뀌지 않아 결제의 updatedAt이 신청 시각입니다.
 */
public record OverdueRefundRequestResult(UUID paymentId, UUID userId, UUID merchantId, Long amount,
                                         CurrencyCode currency, Instant requestedAt, String requestReason) {
    public static OverdueRefundRequestResult from(Payment payment, String requestReason) {
        return new OverdueRefundRequestResult(payment.getId(), payment.getUserId(), payment.getMerchantId(),
                payment.getAmount(), payment.getCurrency(), payment.getUpdatedAt(), requestReason);
    }
}

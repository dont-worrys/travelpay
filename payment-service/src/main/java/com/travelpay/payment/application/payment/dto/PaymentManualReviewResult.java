package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.time.Instant;
import java.util.UUID;

/** 결제 MANUAL_REVIEW 목록 한 줄 */
public record PaymentManualReviewResult(UUID paymentId, UUID userId, UUID merchantId, Long amount,
                                        CurrencyCode currency, SettlementReviewReason reviewReason,
                                        int attemptCount, Instant createdAt, Instant updatedAt) {
    public static PaymentManualReviewResult from(Payment payment) {
        return new PaymentManualReviewResult(payment.getId(), payment.getUserId(), payment.getMerchantId(),
                payment.getAmount(), payment.getCurrency(), payment.getReviewReason(),
                payment.getAttemptCount(), payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
